# Audit findings (open) · owner fixes each in their lane

An independent audit ran on this repo before the first push (scripts and SQL were exercised in a
scratch copy). These are **open**: each owner fixes theirs as early slices, with a DECISIONS.md
entry where noted. Severity: **B** = blocks first PRs/CI or makes an agent build the wrong thing,
**I** = important, **M** = minor.

## Person 4 (@HetanshWaghela) · repo, CI, scripts, rules
| # | Sev | Where | Problem → fix |
|---|---|---|---|
| 1 | B | `.github/workflows/ci.yml` `on.pull_request` | Guard ignores PR body/label edits → add `types: [opened, synchronize, reopened, edited, labeled, unlabeled]`. |
| 2 | B | ci.yml supabase job | `deno check` from `supabase/functions` misses `api/deno.json` → `working-directory: supabase/functions/api`; `deno fmt --check && deno lint && deno check index.ts && deno test --allow-read --allow-env`; exclude `lib/rules_vendor` in deno.json fmt/lint. Same fix in `supabase/AGENTS.md`. |
| 3 | B | ci.yml engine job | `pytest` exits 5 with no tests → `{ uv run pytest -q \|\| [ $? -eq 5 ]; }`. |
| 4 | B | ci.yml android job | Skips silently if `gradlew` missing → fail when `android/settings.gradle.kts` exists without `android/gradlew`. |
| 5 | B | `.claude/lanes.json` | Root `pnpm-lock.yaml` changes fail lane check for P3 → add it to `shared`. |
| 6 | B | `scripts/check-lane.sh` | Renames hide the old path → `git diff --no-renames --name-only`. |
| 7 | B | `scripts/check-lane.sh` | Lane only from branch name (Codex `codex/...` branches fail; anyone can name `p4/x`) → add `authors` map (VrajVed p1, darved2305 p2, nikhilpravinpise p3, HetanshWaghela p4) to lanes.json, pass `PR_AUTHOR` from ci.yml, fail if branch prefix disagrees. |
| 8 | B | `scripts/check-pr.sh` | One-line `<!-- -->` in Proof deletes the section → strip comments/CRs first: `body=$(perl -0777 -pe 's/<!--.*?-->//gs; s/\r//g' <<<"$body")`. |
| 9 | B | `scripts/check-pr.sh` | Proof regex too loose ("run id: n/a" passes); docs-only PRs can't pass honestly → stricter regex (real URL or `ap-`/`fc-` IDs ≥16 chars or a code block), reject `n/a/none/tbd/todo/pending`, allow `Docs only:` when every changed path is `*.md`/`docs/**`/DECISIONS.md. |
| 10 | B | `scripts/check-pr.sh` | Decisions check passes the untouched template and unknown IDs → extract `D-\d{3}` / `D-\d{6}-[A-Z]{2,4}\d+`, require each to exist in DECISIONS.md, require an ID when plan/contract/rule files change, accept `none` case-insensitively. |
| 11 | B | `.github/workflows/ledger.yml` | Records template comments → strip `<!-- -->` before reading fields. |
| 12 | B | ledger.yml | Concurrency keeps only the newest pending run → loop over `gh pr list --state merged --base main --limit 20` and append every PR not yet in LEDGER.md, oldest first. |
| 13 | B | ledger.yml + README owner section | If `main` is protected, the bot push is rejected → use a `LEDGER_TOKEN` secret (owner PAT) when present, or require only the `ci-gate` check. |
| 14 | B | `.claude/hooks/format-lint.sh` | `uv run` from repo root can't find ruff → `uv run --project engine` / `--project services/insights`. |
| 15 | B | engine/AGENTS.md, person-1.md, README | Skill installs write into the repo → install globally (`modal skills install --claude -g`, `npx skills add … -g`), and gitignore third-party skill dirs: `.claude/skills/*` with `!` for our 5 skills, `.agents/*` except `skills`. |
| 16 | B | `.mcp.json` | `${SUPABASE_PROJECT_REF}` unset for P1/P2/P4 → keep only `cloudflare-docs`; P3 adds Supabase MCP with `claude mcp add --scope local`. |
| 27 | I | lanes.json, CODEOWNERS | P3 can edit his own prescriptive spec; `docs/` overlaps → remove `docs/lanes/*` from P1–P3 lanes; spec owners `@HetanshWaghela @nikhilpravinpise`. |
| 28 | I | repo root | Add `.gitattributes`: `DECISIONS.md merge=union`, `*.sh text eol=lf`, `sdk/** linguist-generated`. Point `docs/plan/11-debates.md` open list to DECISIONS.md; DECISIONS D-010 cites "AGENTS.md §Team and lanes"; 06 cites contracts §7; build plan §7 → pointer to AGENTS.md; contract PRs include DECISIONS.md. |
| 29 | I | docs/plan/10-build-plan.md, person-4.md, README | CI has no deploy job → state "deploy manually (P3: `supabase db push`, `supabase functions deploy api`; P1: `modal deploy`)"; README owner checklist: add collaborators, `gh label create cross-lane`, Actions secrets. |
| 30 | I | engine/AGENTS.md, person-4.md, 01 | Golden eval tunes on held-out labels → split `fixtures/eval/dev/` (P1 may tune) from `fixtures/eval/heldout/` (only P4's RESULTS.md run); define eval script location (`packages/eval/`). |
| 32 | I | `.agents/skills` | Codex doesn't read `.claude/skills` → commit symlink `.agents/skills -> ../.claude/skills`; add `.agents/` to the P4 lane. |
| 33 | M | lane-guard.sh | Symlinked paths fail open → canonicalise root and file with `pwd -P`. |
| 26 | I | `.env.example` | Missing `EMBED_SIGNING_KEY`, `API_BASE_URL` (for Modal → /internal), `INSIGHTS_*` if insights stays on Modal; add a table: name, who generates, where stored, who consumes. |
| 34 | done | DECISIONS.md | **Done:** clips in D-261010-HW1 (`fixtures/clips.md`); spend ceiling in D-261010-VV1 (Person 1). Was: no named real clip yet (blocks P1 CLI run, P2 overlay spike, P4 fixtures) → decide a Phase 1 stand-in (e.g. Wikimedia `badminton_eurogames`, CC BY-SA 4.0, see docs/research/footage-sources.md), upload to R2, record licence/attribution. Also fill the Modal spend ceiling. |
| 35 | I | build plan / .env.example | Owners of Supabase project, Cloudflare/R2 account and token hand-offs (INTERNAL_API_TOKEN, MODAL_SUBMIT_TOKEN, R2 keys) are unnamed → add the table. |
| 37 | M | docs/ui | `*.dc.html` need the canvas runtime (`support.js` absent) → export PNG previews into docs/ui. |
| 38 | M | docs/research | Contains superseded material (hour markers, old stack, absolute paths) → add a banner README: evidence only, superseded by DECISIONS.md; agents don't load it unless a decision cites it. |
| 39 | M | docs/lanes/person-*.md line 3 | "Paste this file into your coding agent" → "Your agent reads this file first; AGENTS.md points here." |

## Person 3 (@nikhilpravinpise) · spec corrections (record as one decision, e.g. `D-<date>-NP1`)
| # | Sev | Spec section | Problem → fix |
|---|---|---|---|
| 17 | B | §1, §11, `supabase/AGENTS.md` | `new OpenAPIHono().basePath('/api')` makes the spec list `/api/v1/...` → SDK calls `/api/api/v1`. Use: `const api = new OpenAPIHono(); /* routes under /v1 */ api.doc('/v1/openapi.json', …); const root = new Hono().route('/api', api); Deno.serve(root.fetch);` (tested). |
| 18 | B | §7.3 vs contracts §3 | Internal event body: accept the contract's `decode_window_us` / `emit_range_us` arrays and map them to the four columns; `engine_version` optional (set on `analysis.started`); cursor returns arrays. |
| 19 | B | §5.3, §7.2–7.3 | Replace `append_event` with the tested version below (row lock, dedupe inside the lock, transition table, state + engine_version + failure_reason, warns on Broadcast failure). Add `finalized_at timestamptz` to `analysis_runs`; make finalize one RPC `ingest_vision_result(p_analysis_id, p_result jsonb)` that sets `finalized_at` and appends `analysis.finalizing`; remove `analysis.finalizing` from the Modal-allowed list. State becomes `partial` on the first `window.completed`; `analysis.partial` is not emitted in V1. |
| 20 | B | §7.1 | Dispatch: add `dispatch_claimed_at` lease (stale after 60 s); append `analysis.queued` BEFORE calling Modal; unique index `on analysis_runs(session_id) where state not in ('failed','completed')`; RPCs `create_analysis_run` and `claim_dispatch` (PostgREST can't increment); define `session_spec` = contracts §2 body assembled by SQL. |
| 21 | B | §5.4 | Data model gaps: store `players`, `calibration`, `capabilities`, `models` (jsonb on `analysis_runs`); one name `vision_events` everywhere; `idempotency` table stores the response body (not a hash); child IDs from Person 1 kept as supplied with PK `(match_id, id)`; score = contiguous prefix of rallies with a user-confirmed winner; define columns for consents, claims, share_grants, reference_examples, developer_keys. |
| 22 | B | §5.2 RLS | Sessions insert must check the match is owned: `with check ((select auth.uid()) = owner_id and exists (select 1 from public.matches m where m.id = match_id and m.owner_id = (select auth.uid())))`; write every select-only policy in full; explicit `grant select … to authenticated`, `grant all … to service_role`; DELETE uses admin client after an ownership check. |
| 23 | I | §1, §8 | Vendor `packages/contracts` zod schemas the same way as rules (CI diff check); move scripts to `supabase/scripts/`; rules package must be zero-dependency with explicit `.ts` imports (tell Person 4). |
| 24 | B | §8 vs plan/08 | Missing endpoints: `GET /v1/capabilities` (vendored capabilities.json from P4's RESULTS), `GET /v1/analyses/{id}`, `GET /v1/matches` (list), `GET /v1/sessions/{id}` (upload resume), rallies list, `GET /v1/moments` (cross-match for MCP), history, share-grants, embed-tokens, export; specify `StubAnalysis` (Person 2 depends on it); move developer keys to Phase 3 (MCP needs them). Contract additions need a decision. |
| 25 | I | §10 | Insights on Modal conflicts with "only Person 1 deploys Modal" and has no trigger URL/token → either Person 1 deploys it (add `INSIGHTS_SUBMIT_URL/TOKEN`) or implement insights as SQL functions called from `ingest_vision_result` (recommended: deterministic, no extra service). Hero rule: rank by `abs(num/den − 0.5)` desc, then `den` desc, then key asc; finalize writes moments, insights only tags them; comparisons via SQL function `compare_shot(...)`. |

Tested `append_event` (replace spec §5.3):
```sql
create or replace function public.append_event(p_analysis_id text, p_type text, p_fields jsonb)
returns public.analysis_events language plpgsql set search_path = '' as $$
declare
  v_run public.analysis_runs; v_row public.analysis_events; v_next text;
  v_ws int := (p_fields->>'window_seq')::int;
begin
  select * into v_run from public.analysis_runs where id = p_analysis_id for update;
  if not found then raise exception 'analysis_not_found' using errcode = 'P0002'; end if;
  if p_type <> 'point.confirmed' then
    select * into v_row from public.analysis_events
      where analysis_id = p_analysis_id and type = p_type and window_seq is not distinct from v_ws;
    if found then return v_row; end if;
  end if;
  v_next := case p_type when 'analysis.queued' then 'queued' when 'analysis.started' then 'processing'
    when 'window.completed' then 'partial' when 'analysis.finalizing' then 'finalizing'
    when 'analysis.completed' then 'completed' when 'analysis.failed' then 'failed' end;
  if v_next is null and v_run.state in ('completed','failed') and p_type <> 'point.confirmed' then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' is terminal';
  end if;
  if v_next is not null and not (v_run.state, v_next) in (
      ('uploaded','queued'), ('uploaded','failed'), ('queued','processing'), ('queued','partial'), ('queued','failed'),
      ('processing','partial'), ('processing','finalizing'), ('processing','failed'),
      ('partial','partial'), ('partial','finalizing'), ('partial','failed'),
      ('finalizing','completed'), ('finalizing','failed')) then
    raise exception 'invalid_transition' using errcode = 'P0001', detail = v_run.state || ' -> ' || v_next;
  end if;
  update public.analysis_runs set last_seq = last_seq + 1, updated_at = now(),
         state = coalesce(v_next, state),
         engine_version = coalesce(p_fields->>'engine_version', engine_version),
         failure_reason = case when v_next = 'failed' then p_fields->>'reason' else failure_reason end
   where id = p_analysis_id returning * into v_run;
  insert into public.analysis_events (analysis_id, match_id, owner_id, seq, type, window_seq,
    decode_start_us, decode_end_us, emit_start_us, emit_end_us, artifact_id, reason, engine_version)
  values (p_analysis_id, v_run.match_id, v_run.owner_id, v_run.last_seq, p_type, v_ws,
    (p_fields->>'decode_start_us')::bigint, (p_fields->>'decode_end_us')::bigint,
    (p_fields->>'emit_start_us')::bigint, (p_fields->>'emit_end_us')::bigint,
    p_fields->>'artifact_id', p_fields->>'reason', p_fields->>'engine_version')
  returning * into v_row;
  begin
    perform realtime.send(jsonb_build_object('schema_version',1,'analysis_id',p_analysis_id,'latest_seq',v_row.seq),
                          'analysis_seq', 'analysis:' || p_analysis_id, true);
  exception when others then raise warning 'broadcast_failed: %', sqlerrm;
  end;
  return v_row;
end $$;
revoke all on function public.append_event(text,text,jsonb) from public, anon, authenticated;
grant execute on function public.append_event(text,text,jsonb) to service_role;
```

## Person 1 (@VrajVed) · engine
| # | Sev | Where | Problem → fix |
|---|---|---|---|
| 20 | B | `submit` | Must be idempotent on `analysis_id` (e.g. a `modal.Dict` of spawned IDs) so an API retry never starts two GPU jobs. |
| 30 | I | `engine/eval/` | Tune only on the dev clip set; the held-out set is scored only by Person 4's eval. Create `engine/eval/clips.json` + `baseline.json` from the first accepted real run. |
| 31 | I | engine-health loop | Run it in its own worktree (`git worktree add ../ggw_elevate-health main`) and `git fetch` first; record the last-checked commit in an untracked file and skip the GPU golden run if unchanged; allow `Bash(modal token info)` and the golden `modal run` locally (`.claude/settings.local.json`) so the loop doesn't stall; missing baseline/clips/spend ceiling = `HEALTH: ACTION NEEDED`; use `modal app logs <app> --since 45m`. |
| 15 | B | skills | Install Modal's skill globally: `modal skills install --claude -g`. |

## Person 2 (@darved2305) · Android
| # | Sev | Where | Problem → fix |
|---|---|---|---|
| 4 | B | `android/` | Commit the Gradle wrapper (`gradlew`, `gradle/wrapper/*`) in the first PR, or CI skips the Android job. |
| 15 | B | skills | Install Android skills globally (not into the repo's `.claude/skills`). |
| 24 | I | API needs | `GET /v1/matches` (Matches tab) and `GET /v1/sessions/{id}` (upload resume) are not in the contract yet: agree them with Person 3 via a decision before building those screens. |
