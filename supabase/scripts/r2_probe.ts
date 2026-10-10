// Phase 1 live probe for the R2 multipart flow (person-3-spec.md section 6.1, VERIFY FIRST).
// Not run by tests: it talks to the real bucket.
//
//   R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... R2_BUCKET=... \
//   deno run --config supabase/functions/api/deno.json --allow-env --allow-read --allow-net \
//     supabase/scripts/r2_probe.ts <path-to-video-longer-than-8MiB>
//
// It uses the real lib/r2.ts: create multipart -> presign 2 parts of 8 MiB -> PUT them with
// fetch (the equivalent curl is printed) -> complete -> HEAD and compare the size. The object
// is the first 16 MiB of the file under probe/<timestamp>/source.mp4 (remove it afterwards in
// the Cloudflare dashboard or let a lifecycle rule do it). Exits non-zero on any mismatch.
import { createR2, R2Error } from "../functions/api/lib/r2.ts";

const PART = 8 * 1024 * 1024;

function need(name: string): string {
  const v = Deno.env.get(name);
  if (!v) {
    console.error(`missing env ${name}`);
    Deno.exit(2);
  }
  return v;
}

function redact(url: string): string {
  return url.replace(/X-Amz-Signature=[0-9a-f]+/, "X-Amz-Signature=<redacted>");
}

async function readSlice(
  file: Deno.FsFile,
  offset: number,
  length: number,
): Promise<Uint8Array<ArrayBuffer>> {
  await file.seek(offset, Deno.SeekMode.Start);
  const buf = new Uint8Array(length);
  let got = 0;
  while (got < length) {
    const n = await file.read(buf.subarray(got));
    if (n === null) break;
    got += n;
  }
  return buf.slice(0, got);
}

const path = Deno.args[0];
if (!path) {
  console.error("usage: r2_probe.ts <video file larger than 8 MiB>");
  Deno.exit(2);
}
const size = (await Deno.stat(path)).size;
if (size <= PART) {
  console.error(`file must be larger than 8 MiB (got ${size} bytes)`);
  Deno.exit(2);
}
const expected = Math.min(size, 2 * PART);

const r2 = createR2({
  accountId: need("R2_ACCOUNT_ID"),
  accessKeyId: need("R2_ACCESS_KEY_ID"),
  secretAccessKey: need("R2_SECRET_ACCESS_KEY"),
  bucket: need("R2_BUCKET"),
});
const key = `probe/${Date.now()}/source.mp4`;
let uploadId: string | null = null;

try {
  console.log(`1. create multipart for ${key}`);
  uploadId = await r2.createMultipart(key);
  console.log(`   UploadId: ${uploadId}`);

  const file = await Deno.open(path, { read: true });
  const parts: Array<{ n: number; etag: string }> = [];
  for (const n of [1, 2]) {
    const url = await r2.presignPart(key, uploadId, n);
    console.log(`2.${n} presigned part ${n}: ${redact(url)}`);
    const body = await readSlice(file, (n - 1) * PART, PART);
    console.log(
      `   curl -X PUT --data-binary @<part ${n} bytes ${(n - 1) * PART}..${
        (n - 1) * PART + body.length
      }> '<url>'`,
    );
    const res = await fetch(url, { method: "PUT", body });
    const etag = res.headers.get("etag");
    console.log(`   PUT -> ${res.status}, ETag ${etag}, ${body.length} bytes`);
    if (!res.ok || !etag) {
      throw new Error(
        `UploadPart ${n} failed: ${res.status} ${await res.text()}`,
      );
    }
    parts.push({ n, etag });
  }
  file.close();

  console.log("3. complete multipart");
  await r2.completeMultipart(key, uploadId, parts);
  uploadId = null;

  console.log("4. HEAD object");
  const head = await r2.head(key);
  console.log(`   size ${head?.bytes} (expected ${expected})`);
  if (head?.bytes !== expected) {
    console.error("PROBE FAILED: size mismatch");
    Deno.exit(1);
  }
  console.log(`PROBE OK: presigned UploadPart works on R2; object ${key}`);
} catch (err) {
  const detail = err instanceof R2Error ? `${err.code}: ${err.message}` : err;
  console.error("PROBE FAILED:", detail);
  if (uploadId) {
    try {
      await r2.abortMultipart(key, uploadId);
      console.error("aborted the multipart upload");
    } catch (e) {
      console.error("abort also failed:", e);
    }
  }
  Deno.exit(1);
}
