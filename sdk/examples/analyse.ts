/**
 * A coach's one-command analysis: upload one match video and watch the cloud engine work on it.
 * Hand-written example (the generator never touches examples/). It uses ONLY the generated SDK
 * for every Elevate API call; the two non-SDK calls are the presigned R2 PUTs (R2 is not part of
 * the Elevate API) and the optional download of each window result from its signed URL.
 *
 *   ELEVATE_TOKEN=<user JWT> npx tsx sdk/examples/analyse.ts ~/Downloads/h13g_singles.mp4
 *
 * Environment:
 *   ELEVATE_TOKEN    required. A signed-in user's JWT, sent as `Authorization: Bearer`.
 *   ELEVATE_API_URL  optional. Default: the deployed API.
 *   ELEVATE_APIKEY   optional. The Supabase publishable key, sent as `apikey` if the gateway wants it.
 * Flags:
 *   --me=x,y         where you stand in the first frame, in pixels (default: the frame centre,
 *                    which is NOT a real tap; say so to your coach).
 *   --format=singles|doubles   (default singles)
 *   --no-hits        do not download window results to count hit candidates (saves download URLs).
 *
 * Needs ffprobe on PATH to read the video's facts (size, fps, codec, frame timing). Nothing here
 * is invented: every source value comes from ffprobe or the file itself.
 *
 * Cloudflare budget (docs/ops/cloudflare-budget.md): ONE upload per run, each part is tried at
 * most 3 times, and a part that still fails stops the run (the upload is aborted, never looped).
 */
import { createHash, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { open, stat } from "node:fs/promises";
import { createReadStream } from "node:fs";
import { basename } from "node:path";
import {
  Configuration,
  DefaultApi,
  FetchError,
  ResponseError,
  V1SessionsPostRequestFormatEnum,
  V1SessionsPostRequestModeEnum,
  V1SessionsPostRequestSportEnum,
  V1SessionsPostRequestUploadModeEnum,
  type V1SessionsPostRequestSource,
} from "../src/index";

const MAX_PART_ATTEMPTS = 3;
const POLL_MS = 3000;

function fail(message: string): never {
  console.error(message);
  process.exit(1);
}

const args = process.argv.slice(2);
const flags = new Map(args.filter((a) => a.startsWith("--")).map((a) => {
  const [k, v] = a.slice(2).split("=");
  return [k, v ?? "true"] as const;
}));
const videoPath = args.find((a) => !a.startsWith("--"));
if (!videoPath) fail("usage: npx tsx sdk/examples/analyse.ts <video.mp4> [--me=x,y] [--format=singles|doubles] [--no-hits]");
const token = process.env.ELEVATE_TOKEN;
if (!token) fail("ELEVATE_TOKEN is not set (a signed-in user's JWT).");

const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
if (process.env.ELEVATE_APIKEY) headers.apikey = process.env.ELEVATE_APIKEY;
const api = new DefaultApi(new Configuration({
  basePath: (process.env.ELEVATE_API_URL ?? "https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api").replace(/\/$/, ""),
  headers,
}));

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Error envelope `{error:{code,message}}` from a failed SDK call, for readable output. */
async function describe(e: unknown): Promise<string> {
  if (e instanceof ResponseError) {
    const body = await e.response.clone().text();
    return `HTTP ${e.response.status} ${body}`;
  }
  if (e instanceof FetchError) return `${e.message} (${e.cause instanceof Error ? e.cause.message : String(e.cause)})`;
  return e instanceof Error ? e.message : String(e);
}

// ---- 1. Read the video's real facts --------------------------------------------------------
function probe(path: string): V1SessionsPostRequestSource {
  const ff = (extra: string[]) => execFileSync("ffprobe", ["-v", "error", "-of", "json", ...extra, path], { maxBuffer: 1 << 28 }).toString();
  const info = JSON.parse(ff(["-show_entries", "stream=index,codec_type,codec_name,width,height,r_frame_rate,sample_rate,side_data_list,tags"]));
  const video = info.streams.find((s: { codec_type: string }) => s.codec_type === "video");
  const audio = info.streams.find((s: { codec_type: string }) => s.codec_type === "audio");
  if (!video) fail("no video stream found");
  const [num, den] = String(video.r_frame_rate).split("/").map(Number);
  const rotationSide = (video.side_data_list ?? []).find((d: { rotation?: number }) => d.rotation !== undefined);
  const fmt = JSON.parse(ff(["-show_entries", "format=duration"]));
  const pkts = JSON.parse(ff(["-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "json"]));
  const ptsUs: number[] = pkts.packets.map((p: { pts_time: string }) => Math.round(Number(p.pts_time) * 1e6)).sort((a: number, b: number) => a - b);
  const gapsUs = ptsUs.slice(1).map((t, i) => t - ptsUs[i]);
  const sorted = [...gapsUs].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  // A gap is an interval of at least 1.5x the median; vfr means more than 5% of intervals are
  // more than 10% away from the median (a derived heuristic, stated here, not a container flag).
  const gaps = gapsUs.filter((g) => g >= 1.5 * median).length;
  const vfr = gapsUs.filter((g) => Math.abs(g - median) > 0.1 * median).length > 0.05 * gapsUs.length;
  return {
    width: video.width,
    height: video.height,
    rotation: rotationSide ? Math.round(rotationSide.rotation) : 0,
    fpsNominal: num / den,
    durationMs: Math.round(Number(fmt.format.duration) * 1000),
    pts: { medianIntervalUs: median, minUs: sorted[0], maxUs: sorted[sorted.length - 1], gaps, vfr },
    codec: video.codec_name,
    hasAudio: Boolean(audio),
    audioRateHz: audio ? Number(audio.sample_rate) : 0,
    device: "elevate-sdk-example",
    bytes: 0,
    sha256: "",
  };
}

async function sha256(path: string): Promise<string> {
  const h = createHash("sha256");
  for await (const chunk of createReadStream(path)) h.update(chunk as Buffer);
  return h.digest("hex");
}

// ---- 2. Upload one video to R2 through presigned multipart parts ---------------------------
async function putPart(url: string, body: Uint8Array<ArrayBuffer>): Promise<string> {
  let last = "";
  for (let attempt = 1; attempt <= MAX_PART_ATTEMPTS; attempt++) {
    try {
      const res = await fetch(url, { method: "PUT", body });
      if (res.ok) {
        const etag = res.headers.get("etag");
        if (!etag) throw new Error("R2 returned no ETag");
        return etag;
      }
      last = `HTTP ${res.status}`;
    } catch (e) {
      last = e instanceof Error ? e.message : String(e);
    }
    await sleep(1000 * attempt);
  }
  throw new Error(`part failed after ${MAX_PART_ATTEMPTS} attempts (${last})`);
}

async function main() {
  const path = videoPath as string;
  const { size } = await stat(path);
  console.log(`video: ${basename(path)}  ${(size / 1e6).toFixed(1)} MB`);
  const source = probe(path);
  source.bytes = size;
  source.sha256 = await sha256(path);
  console.log(`source: ${source.width}x${source.height} ${source.fpsNominal} fps ${source.codec}, ${(source.durationMs / 1000).toFixed(1)} s, sha256 ${source.sha256.slice(0, 12)}...`);

  const me = (flags.get("me") ?? `${Math.round(source.width / 2)},${Math.round(source.height / 2)}`).split(",").map(Number);
  if (!flags.has("me")) console.log("note: --me not given, using the frame centre as who-is-me (not a real tap).");

  // Create the session.
  const session = await api.v1SessionsPost({
    idempotencyKey: randomUUID(),
    v1SessionsPostRequest: {
      sport: V1SessionsPostRequestSportEnum.Badminton,
      format: flags.get("format") === "doubles" ? V1SessionsPostRequestFormatEnum.Doubles : V1SessionsPostRequestFormatEnum.Singles,
      ruleset: { id: "bwf_3x21", version: "2025-04" },
      mode: V1SessionsPostRequestModeEnum.Practice,
      source,
      calibrationInput: null,
      whoIsMe: { imageXy: me, tMs: 0 },
      freePlaySeed: null,
      rallyCandidates: [],
      uploadMode: V1SessionsPostRequestUploadModeEnum.Multipart,
    },
  });
  const upload = (session as { upload?: { partSize: number; maxConcurrency: number } }).upload;
  if (!upload) fail(`session has no multipart upload: ${JSON.stringify(session)}`);
  const sessionId = (session as { sessionId: string }).sessionId;
  const partCount = Math.ceil(size / upload.partSize);
  console.log(`session ${sessionId}  match ${(session as { matchId: string }).matchId}  ${partCount} parts of ${upload.partSize} bytes`);

  // Upload the parts (at most 3 at a time), then complete.
  const file = await open(path, "r");
  const etags = new Map<number, string>();
  try {
    const concurrency = Math.min(upload.maxConcurrency || 1, 3);
    for (let first = 1; first <= partCount; first += concurrency) {
      const numbers = Array.from({ length: Math.min(concurrency, partCount - first + 1) }, (_, i) => first + i);
      const { parts } = await api.v1SessionsIdUploadPartsPost({ id: sessionId, v1SessionsIdUploadPartsPostRequest: { partNumbers: numbers } });
      await Promise.all(parts.map(async (part) => {
        const start = (part.n - 1) * upload.partSize;
        const length = Math.min(upload.partSize, size - start);
        const buf = new Uint8Array(length);
        await file.read(buf, 0, length, start);
        etags.set(part.n, await putPart(part.url, buf));
      }));
      console.log(`uploaded ${etags.size} of ${partCount} parts`);
    }
  } catch (e) {
    console.error(`upload stopped: ${await describe(e)}; aborting the upload (no retries on a failed URL).`);
    await api.v1SessionsIdUploadAbortPost({ id: sessionId }).catch(() => undefined);
    process.exit(1);
  } finally {
    await file.close();
  }
  const done = await api.v1SessionsIdUploadCompletePost({
    id: sessionId,
    v1SessionsIdUploadCompletePostRequest: { parts: [...etags].sort((a, b) => a[0] - b[0]).map(([n, etag]) => ({ n, etag })) },
  });
  console.log(`upload ${done.status}: ${done.bytes} bytes`);

  // ---- 3. Start the analysis and follow its events -----------------------------------------
  const analysis = await api.v1AnalysesPost({ idempotencyKey: randomUUID(), v1AnalysesPostRequest: { sessionId } });
  console.log(`analysis ${analysis.analysisId} state ${analysis.state}`);

  let afterSeq = 0;
  let windowsExpected: number | undefined;
  let windowsDone = 0;
  let hits = 0;
  let countHits = !flags.has("no-hits");
  let lastWindowArtifact: string | undefined;
  let state: string = analysis.state;
  let failure: string | undefined;
  while (state !== "completed" && state !== "failed") {
    const page = await api.v1AnalysesIdEventsGet({ id: analysis.analysisId, afterSeq, limit: 100 });
    for (const ev of page.events) {
      if (ev.type === "analysis.started" && ev.windowsExpected !== undefined) windowsExpected = ev.windowsExpected;
      if (ev.type === "analysis.failed") failure = ev.reason ?? "unknown";
      if (ev.type === "window.completed") {
        windowsDone++;
        if (ev.artifactId) {
          lastWindowArtifact = ev.artifactId;
          if (countHits) {
            try {
              const signed = await api.v1ArtifactsIdGet({ id: ev.artifactId });
              const result = (await (await fetch(signed.url)).json()) as { events?: { type: string }[] };
              hits += (result.events ?? []).filter((x) => x.type === "hit").length;
            } catch (e) {
              countHits = false;
              console.log(`(hit counting stopped: ${await describe(e)})`);
            }
          }
        }
        console.log(`window ${ev.windowSeq ?? windowsDone} of ${windowsExpected ?? "?"} analysed (${countHits ? `hit candidates so far: ${hits}` : "hits not counted"})`);
      }
      afterSeq = Math.max(afterSeq, ev.seq);
    }
    state = page.state;
    if (!page.hasMore && state !== "completed" && state !== "failed") await sleep(POLL_MS);
  }

  // ---- 4. Final state and the signed URL of the last window result -------------------------
  const final = await api.v1AnalysesIdGet({ id: analysis.analysisId });
  console.log(`analysis ${final.analysisId} finished: state ${final.state}, engine ${final.engineVersion ?? "unknown"}, last_seq ${final.lastSeq}${failure ? `, failure ${failure}` : ""}`);
  if (lastWindowArtifact) {
    const signed = await api.v1ArtifactsIdGet({ id: lastWindowArtifact });
    console.log(`last window result ${lastWindowArtifact}: ${signed.url} (expires ${signed.expiresAt.toISOString()})`);
  } else {
    console.log("no window result was produced.");
  }
}

main().catch(async (e) => {
  console.error(await describe(e));
  process.exit(1);
});
