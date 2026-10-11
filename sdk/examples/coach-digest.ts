/**
 * A club coach's weekly digest: read several matches with a read-only developer key and print,
 * per player, where they aimed their shots and how their rallies flow, across all the matches.
 * Hand-written example (the generator never touches examples/). Every API call goes through the
 * generated SDK; every number is a count with its denominator, taken from the match record.
 *
 *   ELEVATE_API_KEY=<developer key> npx tsx sdk/examples/coach-digest.ts m_abc m_def m_ghi
 *   ELEVATE_API_KEY=<developer key> ELEVATE_MATCH_IDS=m_abc,m_def npx tsx sdk/examples/coach-digest.ts
 *
 * Environment:
 *   ELEVATE_API_KEY   required. A developer key (create one with POST /v1/developer-keys while
 *                     signed in). It is read-only and is sent as `X-API-Key`.
 *   ELEVATE_API_URL   optional. Default: the deployed API.
 *   ELEVATE_MATCH_IDS optional. Comma-separated match ids, if not given as arguments.
 *
 * Match ids are passed in because the deployed API has no "list my matches" route yet.
 */
import {
  Configuration,
  DefaultApi,
  ResponseError,
  type GetMatch200Response,
} from "../src/index";

const apiKey = process.env.ELEVATE_API_KEY;
const fromArgs = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const fromEnv = (process.env.ELEVATE_MATCH_IDS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
const matchIds = fromArgs.length > 0 ? fromArgs : fromEnv;

function fail(message: string): never {
  console.error(message);
  process.exit(1);
}
if (!apiKey) fail("ELEVATE_API_KEY is not set (a developer key; see the header of this file).");
if (matchIds.length === 0) fail("usage: ELEVATE_API_KEY=... npx tsx sdk/examples/coach-digest.ts <match_id> [<match_id> ...]");

const api = new DefaultApi(new Configuration({
  basePath: (process.env.ELEVATE_API_URL ?? "https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api").replace(/\/$/, ""),
  headers: { "X-API-Key": apiKey },
}));

async function describe(e: unknown): Promise<string> {
  if (e instanceof ResponseError) return `HTTP ${e.response.status} ${await e.response.clone().text()}`;
  return e instanceof Error ? e.message : String(e);
}

type Totals = {
  matches: Set<string>;
  shots: number;
  zones: Map<string, number>;
  inferred: number;
};

const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  if (s.length === 0) return null;
  return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
};

const perPlayer = new Map<string, Totals>();
const rallyLengths: number[] = [];
const rallyMs: number[] = [];
async function main() {
  let read = 0;
  for (const id of matchIds) {
    let match: GetMatch200Response;
    try {
      match = await api.getMatch({ id });
    } catch (e) {
      console.error(`${id}: ${await describe(e)}`);
      continue;
    }
    read++;
    const games = match.score.games.map((g) => g.join("-")).join(", ") || "no score";
    console.log(`${match.matchId}  ${match.format}  ${match.rallies.length} rallies  ${match.shots.length} shots  games ${games}`);
    for (const r of match.rallies) {
      rallyLengths.push(r.shotIds.length);
      rallyMs.push(r.endMs - r.startMs);
    }
    for (const p of match.players) {
      const key = p.identity === "user_confirmed" ? `${p.label} (you)` : `${p.label} (${p.playerId})`;
      const totals = perPlayer.get(key) ?? { matches: new Set(), shots: 0, zones: new Map(), inferred: 0 };
      totals.matches.add(match.matchId);
      for (const s of match.shots.filter((x) => x.playerId === p.playerId)) {
        totals.shots++;
        totals.zones.set(s.chosen.targetZone, (totals.zones.get(s.chosen.targetZone) ?? 0) + 1);
        if (s.chosen.landingState === "inferred") totals.inferred++;
      }
      perPlayer.set(key, totals);
    }
  }
  if (read === 0) fail("no match could be read.");

  console.log(`\nDigest over ${read} of ${matchIds.length} matches`);
  console.log(`rally rhythm: median ${median(rallyLengths)} shots, median ${((median(rallyMs) ?? 0) / 1000).toFixed(1)} s, longest ${Math.max(0, ...rallyLengths)} shots`);
  for (const [player, t] of perPlayer) {
    const top = [...t.zones].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, 3)
      .map(([zone, n]) => `${zone} ${n}/${t.shots} (${Math.round((100 * n) / t.shots)}%)`).join(", ");
    console.log(`${player}: ${t.shots} shots in ${t.matches.size} match(es); most-aimed zones ${top || "none"}; ${t.inferred} of ${t.shots} landings inferred, not observed`);
  }
}

main().catch(async (e) => {
  console.error(await describe(e));
  process.exit(1);
});
