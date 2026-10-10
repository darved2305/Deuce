# mcp/ · Person 3 (@nikhilpravinpise) · read-only MCP server (local stdio first)
Adds to the root AGENTS.md. Spec: `docs/lanes/person-3-spec.md` §MCP.
- `@modelcontextprotocol/server` 2.3.1 (spec 2026-07-28, stateless), Node ≥ 20, zod ^4.2. Use `registerTool` with `z.object()`; stdio from `@modelcontextprotocol/server/stdio`.
- **Never write to stdout** in a stdio server (it corrupts JSON-RPC): log with `console.error`.
- Tools: `get_match_summary`, `find_moments`, `get_rally`, `get_capabilities`. Read-only. Typed filters only; the model never writes SQL.
- Proof: a real tool call answered from the deployed API, shown in Claude (or the MCP Inspector).
- Docs: https://ts.sdk.modelcontextprotocol.io/v2/llms.txt · Context7 `/websites/ts_sdk_modelcontextprotocol_io_v2` · Claude plugin `mcp-server-dev` (anthropics/claude-plugins-official). Do not follow v1 tutorials.
