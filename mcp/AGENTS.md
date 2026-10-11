# mcp/ · Person 3 (@nikhilpravinpise) · read-only MCP server (local stdio first)
Adds to the root AGENTS.md. Spec: `docs/lanes/person-3-spec.md` §MCP.
- `@modelcontextprotocol/server` 2.3.1 (spec 2026-07-28, stateless), Node ≥ 20, zod ^4.2. Use `registerTool` with `z.object()`; stdio from `@modelcontextprotocol/server/stdio`.
- **Never write to stdout** in a stdio server (it corrupts JSON-RPC): log with `console.error`.
- Tools (all read-only, typed inputs, the model never writes SQL): `get_match_summary`, `find_moments` (tags, player, outcome, limit, applied to match.v1), `get_rally`, `get_capabilities`, plus the coach tools `rewatch_picks`, `get_landing_zones`, `compare_moment` (B8), `get_analysis_progress`. Pure shaping lives in `src/coach.ts`; every count carries its denominator and every moment a deep link `elevate://m/{match}/r/{rally}?t_ms=`.
- Setup: self-contained - `npm ci && npm test` in `mcp/` (no repo-root install needed). Contract schemas are vendored into `src/contracts/` by `node scripts/sync-contracts.mjs` (reads `packages/contracts/src`, so it runs inside the repo); keep them in sync and verify with `npm run check-contracts`. `npm run build` emits `dist/` (the `elevate-mcp` bin); `npm install` builds it via `prepare`.
- Credentials: `ELEVATE_API_KEY` (developer key, read-only) or `ELEVATE_JWT`. Env vars only, never committed.
- Try it without a host app: `npx tsx scripts/call.ts rewatch_picks '{"match_id":"m_..."}'`.
- Proof: a real tool call answered from the deployed API, shown in Claude (or the MCP Inspector).
- Docs: https://ts.sdk.modelcontextprotocol.io/v2/llms.txt · Context7 `/websites/ts_sdk_modelcontextprotocol_io_v2` · Claude plugin `mcp-server-dev` (anthropics/claude-plugins-official). Do not follow v1 tutorials.
