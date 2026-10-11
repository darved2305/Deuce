# Elevate as a developer platform: MCP server and TypeScript SDK demo

## Why this matters

Elevate turns one phone video of a badminton match into structured facts: rallies, every shot,
where each shot was aimed, which moments matter, and how similar moments went before. Most
products stop at an app screen. We also expose those facts through an API, so anyone can build on
one phone video:

- **A player asks an AI assistant "which rallies should I rewatch and why?"** The assistant does
  not guess. It calls the Elevate MCP server, which reads the real match and answers with counts
  taken from the record (the assistant never computes statistics) plus a deep link for each pick.
  The link, `elevate://m/{match}/r/{rally}?t_ms=...`, opens the Elevate Android app at that exact
  moment of the video. That is the AI-to-phone round trip: ask in chat, watch on the phone.
- **A club coach runs one script** and gets a weekly digest across many matches: where each
  player aims, and how long their rallies run. Another script goes from a raw video file to a
  finished analysis with one command.
- The same record serves the app, the assistant and the script. No second data pipeline.

Everything is read-only for assistants and developers. A developer key can read but cannot
write, create keys or delete.

## What exists

| Piece | Where | What it does |
|---|---|---|
| MCP server (stdio) | `mcp/` | 8 read-only tools, typed inputs (zod v4), responses checked against the vendored contracts |
| TypeScript SDK | `sdk/` (generated) | Typed client for the deployed API, generated from `/v1/openapi.json` |
| One-command analysis | `sdk/examples/analyse.ts` | Video file to finished analysis: create session, upload in parts, start analysis, follow progress |
| Coach digest | `sdk/examples/coach-digest.ts` | Read several matches with a developer key, print per-player zone shares and rally rhythm |

MCP tools:

| Tool | Answers |
|---|---|
| `get_match_summary` | score, headline insight, rally count, capability tiers, deep link |
| `rewatch_picks` | "which rallies should I rewatch and why?": longest or lost rallies, a checkable reason each, top insights, deep links |
| `get_landing_zones` | where each player aimed, as zone counts with denominators and observed versus inferred |
| `find_moments` | moments of a match, filtered by tags, player, won or lost, limit, each with a deep link |
| `get_rally` | one rally with its shots and a deep link to its start |
| `compare_moment` | the top similar moments to one shot (up to 5), with how often they worked and where they came from |
| `get_analysis_progress` | live state: windows done of expected, hit candidates so far |
| `get_capabilities` | capability tiers, build-wide or for one match |

All of them only read. Counts come from the match record and keep their provenance state
(observed, candidate, inferred, user), so the assistant can say what is measured and what is an
estimate.

## What you need to run it live (credential)

The API rejects every read without a credential. We could not obtain one in this session, so the
live success path below is **not run here** and is marked UNVERIFIED. Exactly what is needed:

1. A Supabase **publishable key** for the project (the same one the Android app is built with,
   `elevate.supabasePublishableKey`). It is not secret, but it is not committed either.
2. Anonymous sign-in, which returns a user JWT:
   `POST https://zpwxjjsugujmihhkmfjx.supabase.co/auth/v1/signup` with header `apikey: <publishable key>`
   and body `{}`. Use the returned `access_token` as `ELEVATE_JWT`.
3. For the developer-key path (the digest, and the MCP server in read-only mode): with that JWT,
   `POST /v1/developer-keys` with an `Idempotency-Key` header. The response contains the key once.
   Put it in `ELEVATE_API_KEY`. A developer key cannot create further keys.
4. A real match id. Use the phone's own match (the app shows it) or the match created by
   `analyse.ts` below. The deployed API has no "list my matches" route yet, so ids are passed in.

Credentials go in environment variables only and are never committed.

## Setup (once)

```bash
pnpm install                      # repo root: the vendored contracts import zod from here
cd mcp && npm ci && npm test      # 20 tests
cd ../sdk/examples && npx tsc --noEmit -p tsconfig.json
```

## Demo A: "Ask your coach" in Claude (60 seconds)

Needs a developer key (or JWT) and one real match id, see above.

1. Register the server (stdio, so it never prints anything but protocol messages):

   ```bash
   claude mcp add elevate \
     -e ELEVATE_API_KEY="$ELEVATE_API_KEY" \
     -- "$PWD/mcp/node_modules/.bin/tsx" "$PWD/mcp/src/index.ts"
   ```

   Claude Desktop: the same command and env in `claude_desktop_config.json` under `mcpServers`.
2. Open Claude and ask: **"In match `<match_id>`, which rallies should I rewatch and why?"**
   Claude calls `rewatch_picks`. It answers with the longest rallies, a reason for each (shot
   count against the match median, whether you lost it, which zone you aimed at), and links.
3. Follow up: **"Which of those did I lose? Where did I aim most?"** Claude calls
   `rewatch_picks` with `lost_rallies` and `get_landing_zones`.
4. Ask: **"Show me similar moments to the first shot in that rally."** Claude calls
   `compare_moment`.
5. On the Android phone, open one of the `elevate://m/.../r/...?t_ms=...` links. The app opens the
   analysed video at that moment. (NOT YET POSSIBLE: a search of `android/` at this commit finds
   no `elevate://` link handling, so the app does not register the scheme yet. See "Open items".)

No host needed? Run the same server directly and watch the protocol:

```bash
cd mcp
ELEVATE_API_KEY=... npx tsx scripts/call.ts rewatch_picks '{"match_id":"<match_id>"}'
```

## Demo B: SDK, from video to analysis, then the coach digest

```bash
# 1. One command from a video file to a finished analysis (needs ELEVATE_TOKEN = user JWT, ffprobe)
ELEVATE_TOKEN="$ELEVATE_JWT" npx tsx sdk/examples/analyse.ts ~/Downloads/match.mp4 --me=640,360
#    prints one line per finished engine window: "window n of N analysed (hit candidates so far: ...)"

# 2. The club coach digest over several matches (needs ELEVATE_API_KEY = developer key)
ELEVATE_API_KEY=... npx tsx sdk/examples/coach-digest.ts m_aaa m_bbb m_ccc
#    prints per-match line, then: rally rhythm, and per player the most-aimed zones as n/N
```

`analyse.ts` honours the Cloudflare budget rules: one upload per run, each part tried at most 3
times, and a part that still fails stops the run (the upload is aborted, never looped).
Without `--me` it uses the frame centre as the "who is me" point and says so.

## Real transcript (what we got from the deployed API)

Run on 2026-10-11 against `https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api`. These calls
are real. They prove the path and the failure mode; they are not a successful match read, because
no credential was available.

The API is up, and the deployed route list (from `/v1/openapi.json`):

```text
$ curl -s $API/v1/health
{"status":"ok"}

GET /v1/health
POST /v1/analyses
GET /v1/analyses/{id}
GET /v1/analyses/{id}/events
POST /v1/developer-keys
DELETE /v1/developer-keys/{id}
GET /v1/artifacts/{id}
GET DELETE /v1/matches/{id}
POST /v1/matches/{id}/reviews/{rvid}/confirm
GET /v1/matches/{mid}/shots/{shot_id}/comparisons
POST /v1/matches/{id}/corrections
POST /v1/sessions
POST /v1/sessions/{id}/segments
POST /v1/sessions/{id}/segments/{seq}/complete
GET /v1/sessions/{id}
POST /v1/sessions/{id}/upload/parts
POST /v1/sessions/{id}/upload/complete
POST /v1/sessions/{id}/upload/abort
```

Reads without a credential return the real error envelope:

```text
$ curl -s -w ' [HTTP %{http_code}]' $API/v1/matches/m_demo
{"error":{"code":"unauthorized","message":"unauthorized"}} [HTTP 401]
```

The MCP server over real stdio, listing its tools and then calling one with no credential and with
an invalid key (`npx tsx scripts/call.ts ...`):

```text
> initialize
> tools/list
  get_match_summary [read-only]
  find_moments [read-only]
  get_rally [read-only]
  get_capabilities [read-only]
  rewatch_picks [read-only]
  get_landing_zones [read-only]
  compare_moment [read-only]
  get_analysis_progress [read-only]
> tools/call {"name":"rewatch_picks","arguments":{"match_id":"m_demo"}}
isError: true
Set ELEVATE_API_KEY or ELEVATE_JWT to query the API.

$ ELEVATE_API_KEY=invalid-demo-key ... get_match_summary '{"match_id":"m_demo"}'
isError: true
API returned HTTP 401 (unauthorized): unauthorized
```

The SDK path (`DefaultApi.v1MatchesIdGet` with an `X-API-Key` header), and the upload example
failing at session creation with a bad token:

```text
$ ELEVATE_API_KEY=invalid-demo-key npx tsx sdk/examples/coach-digest.ts m_demo
m_demo: HTTP 401 {"error":{"code":"unauthorized","message":"unauthorized"}}
no match could be read.

$ ELEVATE_TOKEN=invalid npx tsx sdk/examples/analyse.ts probe.mp4
video: probe.mp4  0.0 MB
source: 320x240 30 fps h264, 1.0 s, sha256 6e7f8072d64d...
note: --me not given, using the frame centre as who-is-me (not a real tap).
HTTP 401 {"error":{"code":"unauthorized","message":"invalid token"}}
```

(`probe.mp4` was a one-second ffmpeg test pattern, used only to reach the API; no match data came
from it.)

## Verified and unverified

| Step | Status |
|---|---|
| Build, typecheck and 20 MCP tests, SDK examples typecheck, digest test through the SDK | verified (local stub servers) |
| MCP stdio server lists 8 tools, nothing but JSON-RPC on stdout | verified |
| Deployed API reachable, real error envelope through MCP and SDK | verified, transcript above |
| `rewatch_picks`, `get_landing_zones`, `compare_moment`, `get_analysis_progress` on a real match | UNVERIFIED: needs a credential and a real match id |
| `coach-digest.ts` and `analyse.ts` success path on real data | UNVERIFIED: same reason |
| Claude Code or Claude Desktop calling the server | UNVERIFIED: not run; use `scripts/call.ts` as the fallback |
| Phone opening an `elevate://m/.../r/...?t_ms=` link at that moment | NOT YET POSSIBLE: no `elevate://` handler found in `android/` |

## Open items found while building

- The deployed OpenAPI document does not yet list the read pages (list matches, rallies, moments,
  capabilities). The new tools therefore read everything from `GET /v1/matches/{id}` (the full
  match record), which is deployed. `find_moments` filters and `get_rally` use it too, so they work
  today. Without a `match_id`, `find_moments` and `get_capabilities` still need the pages route
  and say so.
- The generated SDK predates comparisons, developer keys and segment upload, so it has no methods
  for them. Regenerating (`supabase/scripts/gen_sdk.sh`) will add them. The MCP server calls
  comparisons directly with the same validation.
- The SDK generator is told to leave `sdk/examples/` and `sdk/tsconfig.json` alone
  (`sdk/.openapi-generator-ignore`); the tsconfig excludes `examples` so the SDK build is
  unchanged.
- The Android app has no `elevate://` link handling at this commit (searched `android/`). The
  deep links are emitted in the spec's format, but closing the AI-to-phone loop needs an intent
  filter on the match overview or replay screen that reads `m`, `r` and `t_ms`. This is the one
  missing piece for the full round trip and is Android-lane work, not done here.
- The deployed OpenAPI describes the developer-key response only as "pending"; we read it as an
  object with a `key` field from the route source.
