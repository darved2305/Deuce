# Person 3 finals showcase: the MCP server in Claude, and the SDK in one sentence

Status: rehearsed 2026-10-11 against the deployed API. Everything below was run; the parts that were
not are marked UNVERIFIED.

## The demo match (real, made for this)

| | |
|---|---|
| Source | 60 s slice of the real `h13g_singles` clip (31-91 s), re-encoded video only, 1920x1080, 50 fps |
| Made with | the SDK example `sdk/examples/analyse.ts`: one command, video file to finished analysis |
| Match | `m_e4e43ddfcae54f6aa09808b535d0fb95` |
| Analysis | `a_af59a659bf524ef5a078ae7bbe2d0177`, state `completed`, engine 0.1.0, 10 windows |
| Account | a fresh anonymous Supabase account (owner of the match); "who is me" tap was chosen by the operator at 1145,700 in frame 0 |
| What the record holds | 37 engine events: 32 `hit` and 5 `landing`, all state `candidate`. 0 rallies, no score, no insights, no calibration (no corner taps were given), capability statuses `unknown` |

The engine does not produce rallies or shots yet, so the tools that need them (`rewatch_picks`,
`get_rally`, `get_landing_zones`, `compare_moment`) answer with honest zeros on this match. We do
not fill that gap with invented data. The pitch is the honesty: the assistant says what is measured,
what is only a candidate, and what it cannot tell yet.

## Setup (once per machine, 2 minutes)

The server is registered in Claude Code at user scope as `elevate`:

```text
claude mcp add --scope user elevate -- node C:/Users/Nikhil/elevate-notes/mcp-demo/launch.mjs
claude mcp list        # elevate: Connected
```

`launch.mjs` and `state.json` live outside git in `C:\Users\Nikhil\elevate-notes\mcp-demo\`. The
launcher refreshes the demo account's JWT on every start (an anonymous access token lasts 1 hour)
and stores the rotated refresh token, so it must not run twice at the same instant. It starts
`mcp/src/index.ts` from the checkout named in `ELEVATE_MCP_DIR` (default: the finals worktree).
No token is ever printed or committed. Restart Claude Code after changing the registration, then
type `/mcp` and confirm `elevate` shows its 10 tools.

## On stage: what to ask Claude (in order)

1. **"Using the elevate tools, what can you learn about match m_e4e43ddfcae54f6aa09808b535d0fb95?
   What has the engine detected, how sure is it, and what can it not tell me yet? Quote counts exactly."**
   Run through Claude on 2026-10-11. It called the tools and answered: 37 events, 32 hits and 5
   landings, every one a `candidate` found from ball trajectory only, rally count 0, empty score,
   capability statuses `unknown`, and it declined to treat candidates as facts.
   Say: "It is quoting the record. It never computes a statistic, and it says what it does not know."
2. **"Show me the landing events and when they happened."** Tool `list_events` with type `landing`.
   Verified through the server (`scripts/call.ts`): 5 landings at 10,620, 18,960, 42,740, 52,840 and
   58,300 ms, each with a 10 ms timing uncertainty. Not yet run through Claude.
3. **"Is that analysis still running?"** (give it `a_af59a659bf524ef5a078ae7bbe2d0177`). Tool
   `get_analysis_progress`. Verified through the server on this run mid-flight (10 windows
   expected, hit candidates counted from the real window results). Now answers `completed`.
4. **"Which rallies should I rewatch?"** Tool `rewatch_picks`. Expected honest answer: none, there
   are no rallies in the record yet. Use this on purpose as the abstention beat. Not yet run
   through Claude.

If Claude hesitates to call a tool, say "use the elevate MCP tools". All tools are read-only
(`readOnlyHint`), so no permission prompt for writes can appear.

## Fallbacks

- Claude host misbehaves: the MCP Inspector, `npx @modelcontextprotocol/inspector node
  C:/Users/Nikhil/elevate-notes/mcp-demo/launch.mjs` (UNVERIFIED, not run), or the protocol view
  that is verified: `cd mcp && ELEVATE_JWT=... npx tsx scripts/call.ts list_events '{"match_id":"..."}'`.
- JWT problem: the launcher already refreshes it; if it prints `token refresh failed`, create a new
  anonymous account (`POST /auth/v1/signup` with the publishable key) and re-run the analysis.
- Network down: show the recorded outputs in the PR proofs (#176, #178), say "recorded earlier".

## Talking about the SDK (30 seconds, no live run needed)

- "The TypeScript SDK is generated from the same OpenAPI document the API publishes
  (OpenAPI Generator 7.26.0, `typescript-fetch`). It is never hand-edited, so it cannot drift."
- "This very match was created with the SDK: `sdk/examples/analyse.ts` took the video file,
  created the session, uploaded it in parts, started the analysis and followed the engine's
  progress. One command."
- "The MCP server and the SDK read the same `match.v1` record, with the same IDs the app uses.
  One record, three doors: the app, the SDK for developers, and MCP for AI assistants."
- Do not show `coach-digest.ts`: it needs a developer key, and a developer key is read-only for
  `GET /v1/analyses/{id}` only (`auth.ts`), so it cannot read matches. This is a known limit, not a
  bug to hide; say it if asked.

## Known limits to say out loud if asked

- No `elevate://` link in this demo, because there are no rallies to link to. The Android app does
  handle `elevate://m/{match}/r/{rally}?t_ms=` links (PR #138); that round trip is UNVERIFIED here.
- The first engine run on this clip failed on window 2 with `need at least 2 emitted frames to
  measure quality, got 1` (issue #173); the retry on a clean re-encode completed. The cause is not
  established; it did not reproduce with the engine's own decoder locally.
- Cold start took about 6 minutes on the first run.
