# supabase/ · Person 3 (@nikhilpravinpise) · API, database, events
Adds to the root AGENTS.md. **Implement exactly `docs/lanes/person-3-spec.md`.** It makes every
design decision for this folder; if something you need is not in it, stop and ask Person 4 (or
write a decision). Never invent tables, routes, fields or states.

## Layout
`supabase/config.toml` · `supabase/migrations/<timestamp>_<name>.sql` · `supabase/functions/api/`
(`index.ts`, `deno.json`, `routes/`, `lib/`) · `supabase/functions/.env` (local only, gitignored).

## Commands
- Local stack (needs Docker/OrbStack): `supabase start` · `supabase db reset`
- DB checks: `supabase db lint --local --fail-on error` · `supabase test db`
- Function checks (from supabase/functions): `deno fmt --check && deno lint && deno check api/index.ts && deno test --allow-env`
- Serve locally: `supabase functions serve api --env-file supabase/functions/.env`
- New migration: `supabase migration new <name>` (never edit a merged migration; new timestamps only)
- Deploy (CI or you, from main): `supabase db push` · `supabase functions deploy api`

## Rules (verified in Supabase docs)
- RLS on every table in the same migration that creates it; one policy per operation `to authenticated`; use `(select auth.uid())`; index policy columns; `with check` on insert/update.
- The app gets only the publishable key. Secret keys stay in the Edge Function / insights job. Custom function secrets cannot start with `SUPABASE_`.
- Hono `basePath('/api')` (the function name). `verify_jwt = false` for `api` in config.toml; auth happens in our middleware.
- Realtime private channels: policies on `realtime.messages` using `realtime.topic()`; never `ALTER TABLE realtime.messages`; never create objects in the `realtime` schema.
- Edge limits: 2 s CPU per request, 150 s wall clock, 256 MB: no video bytes, no heavy compute.
- R2: Create/Complete multipart are signed server-side requests; only per-part PUTs are presigned (smoke-test first; fallback in the spec).

## Docs and tools
Supabase MCP (in `.mcp.json`, read-only, dev project) · `npx skills add supabase/agent-skills --skill supabase --skill supabase-postgres-best-practices` ·
`npx skills add honojs/skills` · `npx skills add denoland/skills --skill deno` · `npx skills add https://github.com/cloudflare/skills` ·
https://supabase.com/llms.txt (+ /llms/js.txt, /llms/cli.txt) · https://hono.dev/llms.txt · https://developers.cloudflare.com/r2/llms.txt ·
Context7 `/supabase/supabase`, `/honojs/middleware`, `/mhart/aws4fetch`
