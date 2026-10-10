# Final review verdict

**Do not freeze the hour 0 interfaces yet.** The chosen native Android stack and Supabase plus Modal architecture are viable, and the build pack reflects the major team decisions. The remaining blockers are interface ownership, missing auth and route details, inconsistent contract fields, incomplete sport rules, and demo evidence that is not yet secured.

I read the listed build pack and four lane briefs, then checked their vision, architecture, and demo claims against the supporting verdicts and probe manifest. This was read-only. **UNVERIFIED** means the reviewed sources or artifacts do not establish the claim.

## Numbered issues

1. **The kickoff points to files that do not exist.**  
   **Location:** [README.md §Start here and §What is in this pack](/Users/Hetansh/Github/elevate1.0/README.md:8).  
   **Problem:** It tells the team to read `BLUEPRINT.md` and lists it as the full plan. Neither `BLUEPRINT.md` nor `docs/00` is present. The start sequence also skips the final decisions in `docs/11-debates.md`.  
   **Replace with:** “Read `docs/11-debates.md` first for final choices. Then read `docs/01-ps-fundamentals.md` through `docs/12-real-clips.md`, followed by `docs/contracts.md` and your lane brief. The topic documents are the complete build pack.” Remove the `BLUEPRINT.md` row.

2. **The API host decision still has a Cloudflare Worker fallback.**  
   **Location:** [docs/03-architecture-and-streaming.md §4](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:73), [lanes/person-3.md §Stack](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:24).  
   **Problem:** Both retain a Worker API fallback. `docs/11` D2 selects one Hono API on Supabase Edge Functions and explicitly rules out a Cloudflare Worker API. Workers AI for optional captions is a separate service. Supabase documents Hono routing on Edge Functions. [Supabase routing](https://supabase.com/docs/guides/functions/routing)  
   **Replace with:** “MVP API host: Hono on Supabase Edge Functions only. If the R2 signing probe fails, stop at that checkpoint and resolve it on the selected host. Workers AI may remain an optional caption service; it is not an API host.”

3. **The deployed API base URL and one planned endpoint are missing from the contract.**  
   **Location:** [docs/08-developer-platform.md §§2–3](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:15), [docs/10-build-plan.md §3](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:51).  
   **Problem:** The API lists bare `/v1/...` routes, while Supabase Edge Functions require the function name in the public path. `docs/10` also calls for `/v1/health`, which is absent from the endpoint list.  
   **Replace with:** “Deploy Edge Function `api`. Public base URL: `{SUPABASE_URL}/functions/v1/api`; routes below are relative to that base. Add `GET /v1/health` and `GET /v1/openapi.json` to the endpoint list. Set the OpenAPI `servers` URL to the same base.”

4. **Analysis can be marked complete before its insights exist.**  
   **Location:** [docs/03-architecture-and-streaming.md §5](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:84), [lanes/person-1.md §Interfaces](/Users/Hetansh/Github/elevate1.0/lanes/person-1.md:25), [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:42), [docs/10-build-plan.md §1](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:15).  
   **Problem:** Person 1 is described as writing the whole `match.v1` and emitting `analysis.completed`. Person 3’s insights job runs after that event and writes fields included in `match.v1`. The API is also assigned to assemble the record. “Completed” therefore has competing owners and may precede required data.  
   **Replace with:** “Person 1 writes per-window vision artifacts and a final vision result, plus any clips it owns. Person 3 owns normalized database writes, the insights job, and final `match.v1` assembly. The analysis stays `finalizing` until all required records and artifacts are committed. Person 3 then appends exactly one `analysis.completed` event.”

5. **The history table name changes between documents.**  
   **Location:** [docs/06-coaching-intelligence.md §4](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:66), [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:31).  
   **Problem:** History calls the immutable table `analysis_runs`; the migration plan names `analyses`. Re-analysis is supposed to create a new run, so the stored identity and relationship to a match need one name and rule.  
   **Replace with:** “Use `analysis_runs` as the table name. Each immutable run has one `analysis_id`; `analysis_events` reference that ID. Re-analysis creates a new run and an event correspondence map.” Change the migration list to match.

6. **Auth, keys, embed tokens, sharing, and deletion lack a complete contract.**  
   **Location:** [docs/08-developer-platform.md §§2–4](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:15), [docs/12-real-clips.md §§2, 7](/Users/Hetansh/Github/elevate1.0/docs/12-real-clips.md:20), [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:31).  
   **Problem:** The docs promise Supabase JWTs, developer API keys, short-lived embed tokens, consented cross-account clips, and match deletion, but define no key creation or revocation, token minting, grant creation or revocation, or match delete endpoint. The OpenAPI security schemes and RLS ownership fields are also missing.  
   **Replace with:** “App requests use `Authorization: Bearer <Supabase access JWT>`. Developer requests use `X-API-Key`; keys are generated and revoked by an authenticated owner and never sent to browser embeds. Add `POST /v1/developer-keys`, `DELETE /v1/developer-keys/{id}`, `POST /v1/matches/{id}/embed-tokens` returning a read-only, match-scoped token with expiry, `POST` and `DELETE /v1/matches/{id}/share-grants`, and `DELETE /v1/matches/{id}`. Define the OpenAPI security schemes, token claims, ownership columns, consent scope, and deletion behavior.”

7. **The reinstall acceptance test conflicts with anonymous-only identity.**  
   **Location:** [docs/01-ps-fundamentals.md T1](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:33), [docs/03-architecture-and-streaming.md §4](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:66), [docs/08-developer-platform.md §2](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:15).  
   **Problem:** T1 requires history to survive app reinstall, while the default is anonymous first and no identity-link flow is described. Supabase says an anonymous user cannot recover access after clearing app data or moving to another device unless an identity is linked. [Supabase anonymous sign-ins](https://supabase.com/docs/guides/auth/auth-anonymous)  
   **Replace with:** “Anonymous users may use one installation. Before claiming cross-install history, require the user to link a recoverable identity and test reinstall recovery. Until linked, show that uninstalling or clearing app data loses access to that anonymous account.”

8. **The Realtime payload and event IDs differ across the contract and lane.**  
   **Location:** [docs/contracts.md §2](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:60), [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:35).  
   **Problem:** The contract Broadcast includes `schema_version`, but Person 3’s implementation prompt omits it. The durable row uses `"event_id": "uuid"` despite the ID convention requiring the `e_` prefix.  
   **Replace with:** “Broadcast exactly `{schema_version: 1, analysis_id: "a_…", latest_seq}`. Durable event rows use `event_id: "e_…"`. Use the same payload and ID format in the SQL fixture, API, and Android client.”

9. **The event cursor does not define what `after_seq` means.**  
   **Location:** [docs/contracts.md §2](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:71), [docs/08-developer-platform.md §3](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:30), [lanes/person-2.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-2.md:48).  
   **Problem:** The clients are told to refetch after the last contiguous sequence, but the cursor is not defined as inclusive or exclusive. `next_after_seq` is also undefined when a page is empty.  
   **Replace with:** “`after_seq` is exclusive and equals the last contiguous sequence already applied; use `0` for the first page. `next_after_seq` is the highest returned sequence, or the input cursor if no rows were returned.”

10. **Window event times do not match the artifact window.**  
    **Location:** [docs/contracts.md §§2–3](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:64).  
    **Problem:** The event reports a 12.0–18.0 second range; its result artifact reports 12.0–18.75 seconds. The latter appears to include overlap, but the contract does not distinguish decode context from the interval credited to this window.  
    **Replace with:** “Artifacts contain `decode_window_us: [12000000, 18750000]` and `emit_range_us: [12000000, 18000000]`. `window.completed` reports the same `emit_range_us`; only that non-overlap range advances user-visible window coverage.”

11. **Analysis states and event types leave partial failure and cancellation undefined.**  
    **Location:** [docs/contracts.md §2](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:58), [docs/03-architecture-and-streaming.md §5](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:77).  
    **Problem:** `cancelled` is a state with no cancel endpoint or event. The finalizer can encounter an explicit window failure, but there is no `window.failed` event. `capability_status: "partial"` is also undefined and mixes capability status with analysis state.  
    **Replace with:** “MVP states are `awaiting_upload`, `uploaded`, `queued`, `processing`, `partial`, `finalizing`, `completed`, and `failed`. `partial` means the first user-visible result is available while work continues. Emit `window.failed` with its range and reason. Cancellation is deferred. Use `capability_tier: core|assisted|experimental|unsupported`; do not use `partial` as a capability tier.”

12. **Review records have two different schemas and labels.**  
    **Location:** [docs/07-ai-referee.md §8](/Users/Hetansh/Github/elevate1.0/docs/07-ai-referee.md:94), [docs/contracts.md §4](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:127).  
    **Problem:** The referee document requires time, evidence frames, contact coordinates, and covariance. The canonical `reviews[]` example omits them. The machine enum is `TOO_CLOSE`, while the interface label is “TOO CLOSE TO CALL,” with no stated mapping.  
    **Replace with:** “`match.v1.reviews[]` is the canonical `ReviewEvent`: `review_id, match_id, rally_id, event_id, t_ms, evidence_frames, contact_xy_m, cov_m2, line_id, margin_m, sigma_m, estimate, visibility, model_ids, calibration_id, confirmed_point_winner?, confirmed_by?, confirmed_at?`. Machine value `TOO_CLOSE` renders as `TOO CLOSE TO CALL`.”

13. **Per-field provenance is required globally but absent from the examples.**  
    **Location:** [docs/contracts.md §§0, 4](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:18), [docs/06-coaching-intelligence.md §1](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:20).  
    **Problem:** The contract promises provenance for every derived value, and coaching requires per-field state. The `shots` example has no `quality` object or per-field state for roles, zones, and outcomes.  
    **Replace with:** “Every derived shot field carries a sibling state from `observed|candidate|inferred|user`. Add a `quality` object with `camera_quality_grade` and state fields for player positions, source and landing zones, target, flight time, context, and outcome. A value with no state is not part of `match.v1`.”

14. **The model registry spells TrackNetV3 three different ways.**  
    **Location:** [docs/contracts.md §§3–4](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:81).  
    **Problem:** The identifier is `trackernet_v3`, but the checkpoint is `TrackNet_best.pt` and other documents call it TrackNetV3. This spelling flows into model IDs and reviews.  
    **Replace with:** “Use canonical ID `tracknet_v3` in the capability manifest, window artifact, analysis record, and review `model_ids`.”

15. **The model manifest omits a field required by the vision spec.**  
    **Location:** [docs/contracts.md §§3, 7](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:76), [docs/04-vision-engine.md §5](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:89).  
    **Problem:** `docs/04` requires a source URL; neither contract manifest shape includes it. The window model entry also differs from the capability manifest. The WASB checkpoint license remains **UNVERIFIED** in the pack.  
    **Replace with:** “Define one `ModelDescriptor` used by both artifacts and `/v1/capabilities`: `name, source_url, file, sha256, bytes, code_license, weights_license, trained_on[], used_for[]`. Record checkpoint terms per file; use `UNVERIFIED` until verified.”

16. **`@app/rules` has no defined package name or score-state schema.**  
    **Location:** [docs/contracts.md §6](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:161), [lanes/person-2.md §§Stack, Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-2.md:22), [lanes/person-4.md §Work](/Users/Hetansh/Github/elevate1.0/lanes/person-4.md:26).  
    **Problem:** The workspace path is `packages/rules`, but the import is `@app/rules`; no workspace alias or package metadata defines it. Functions are listed, but `RulesState`, team identity, service order, and vector input/output shapes are not.  
    **Replace with:** “Name the package `@elevate/rules` in `packages/rules/package.json`. Define `RulesState` with ruleset ID/version, sport and format, teams, current game scores, serving player/team, service court, court ends, and match status. Each golden vector contains an initial state, point-winner team, and expected next state.”

17. **The six-sport claim exceeds the rules and court definitions.**  
    **Location:** [docs/01-ps-fundamentals.md C1](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:25), [docs/04-vision-engine.md §§1, 4](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:7), [docs/contracts.md §§0, 5–6](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:11).  
    **Problem:** C1 describes six versioned sport packs, including court template, rules, ball profile, events, and zones. The contract defines rules only for badminton, TT, tennis, and free play; it has no ball profiles or court-template registry. Squash, padel, and pickleball are described as having court and rules support without corresponding definitions.  
    **Replace with:** “V1 support: badminton rules and court pack; TT score and court pack with ball tracking unsupported; tennis manual or assisted score with static-view tracking only; squash, padel, and pickleball unsupported across court, rules, and tracking until their packs exist. C1 is a partial answer, not a six-sport pass.” Add a `SportPack` registry with dimensions, rule IDs, ball profile, event vocabulary, and zone grid for every sport claimed as supported.

18. **Win probability is in the checklist but expressly excluded later.**  
    **Location:** [docs/01-ps-fundamentals.md I3](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:64), [docs/06-coaching-intelligence.md §6](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:103), [docs/11-debates.md D4](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:85).  
    **Problem:** I3 promises a designed Markov win probability; D4 and the simulation spec say there is no win probability.  
    **Replace with:** “Score-state win probability: excluded from this build. Revisit only with a defined model and held-out match-level evaluation.”

19. **The referee test can pass by abstaining on every case.**  
    **Location:** [docs/01-ps-fundamentals.md T6](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:38).  
    **Problem:** “Zero wrong calls among non-abstentions” has no minimum call coverage. An engine that abstains on all 20 bounces satisfies the stated error condition.  
    **Replace with:** “Report wrong calls, call coverage, and abstention rate. Pass only if zero wrong IN/OUT calls and a minimum coverage threshold set before evaluation are both met. Until the team sets that threshold, T6 status is `unknown`.”

20. **“Core” is presented as both a target and an achieved status.**  
    **Location:** [docs/01-ps-fundamentals.md §§Tiers, B, E](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:8), [docs/04-vision-engine.md §4](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:76).  
    **Problem:** The tier definition says Core is built, evaluated, and in the demo, but rows already mark history, overview, API, and mobile as Core before the results file exists. `docs/04` calls its matrix a starting state.  
    **Replace with:** “Tier is the target capability, not current verification status. Every row has a separate status, initially `unknown`; only `fixtures/eval/RESULTS.md` can change it to `pass`, `unmet`, or `could-not-run`.”

21. **The Android lane reintroduces physical speed.**  
    **Location:** [lanes/person-2.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-2.md:39).  
    **Problem:** “Speeds from original frames” conflicts with the final rule: flight time only for racket sports, image-space `px/s` only for free play.  
    **Replace with:** “Show flight time from original PTS. Do not show physical speed. Free play may show image-space `px/s`, labelled as image motion.”

22. **The Mendeley probe is described as phone-match evidence.**  
    **Location:** [README.md §What we measured](/Users/Hetansh/Github/elevate1.0/README.md:33), [docs/04-vision-engine.md §1](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:15).  
    **Problem:** The probe source manifest describes nine 1–1.9 second single-stroke clips from a Mendeley dataset; it does not record the capture device. The stated 50.8% is candidate coverage, not accuracy. The roughly 85% figure is an eyeballed estimate without point-level ground truth. [Mendeley dataset](https://data.mendeley.com/datasets/3sp4xntp34/1), [footage manifest](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md:12), [D3 verdict](/Users/Hetansh/Github/elevate1.0/research/out/D3-vision-engine.md:11).  
    **Replace with:** “On nine 1–1.9 second Mendeley single-stroke clips, the WASB run emitted candidates in 186 of 366 processed slots (50.8% candidate coverage). Capture device is **UNVERIFIED**. The roughly 85% crop estimate is not ground-truth precision. These clips do not establish full-rally or match performance.”

23. **The demo does not name a guaranteed rights-cleared racket clip.**  
    **Location:** [docs/09-demo.md §Run sheet](/Users/Hetansh/Github/elevate1.0/docs/09-demo.md:19), [lanes/person-4.md §§Footage, Eval](/Users/Hetansh/Github/elevate1.0/lanes/person-4.md:31), [docs/11-debates.md D6 and open decisions](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:121).  
    **Problem:** The live college clip is free play; the team cannot film a proper court match there. The later badminton demo assumes a team-recorded or rights-checked match, while footage rights and clip selection remain open. The Mendeley single-stroke probe does not supply match-level footage. **UNVERIFIED:** that a suitable clip is available and cleared.  
    **Replace with:** “The live venue recording demonstrates Free Play only. The racket demo uses a named, rights-cleared source clip with attribution recorded before rehearsal. If no suitable match or rally clip is cleared, label the racket section unavailable and do not present the single-stroke probe as a match.”

24. **The capture modes are targets, not verified outputs from the actual phones.**  
    **Location:** [docs/04-vision-engine.md §3](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:57), [docs/07-ai-referee.md §6](/Users/Hetansh/Github/elevate1.0/docs/07-ai-referee.md:80), [lanes/person-4.md §Footage](/Users/Hetansh/Github/elevate1.0/lanes/person-4.md:31).  
    **Problem:** The plan specifies 1080p60 on the S23 FE and 240 fps on the A55, but no saved test file or PTS metadata from these exact phones is cited. Samsung lists the A55’s normal recording as 4K30 and its 240 fps mode as HD slow motion; this does not prove the planned app-captured files. [Samsung A55 specifications](https://www.samsung.com/uk/smartphones/galaxy-a/galaxy-a55-5g-awesome-lilac-256gb-sm-a556blvceub/), [Samsung S23 FE specifications](https://www.samsung.com/in/smartphones/galaxy-s/galaxy-s23-fe-orange-128gb-sm-s711bzobins/).  
    **Replace with:** “Treat 1080p60 and 240 fps as target modes until the exact phone files are recorded and audited for actual frame rate, PTS, codec, and resolution. Use the verified mode in the eval and demo.”

25. **The measured M4 WASB speed figures are unreconciled.**  
    **Location:** [research/out/D3-vision-engine.md §3](/Users/Hetansh/Github/elevate1.0/research/out/D3-vision-engine.md:46), [research/probes/balltrack/out/wasb_tennis_on_artlabss_mps.json](/Users/Hetansh/Github/elevate1.0/research/probes/balltrack/out/wasb_tennis_on_artlabss_mps.json).  
    **Problem:** The shared context reports roughly 50 fps on MPS, while the saved tennis run reports 9.43 total frames per second over 900 frames. These may use different timing boundaries, but that is **UNVERIFIED**. Neither figure is a Modal result.  
    **Replace with:** “WASB MPS throughput is **UNVERIFIED** until the benchmark boundary is reconciled. Report the saved 9.43 total fps only for that 900-frame run; do not use 50 fps in the demo or deployment estimate without a matching reproducible measurement.”

26. **Dependency ranges and wildcards violate the pack’s pinning rule.**  
    **Location:** [lanes/person-1.md §Stack](/Users/Hetansh/Github/elevate1.0/lanes/person-1.md:29), [lanes/person-2.md §Stack](/Users/Hetansh/Github/elevate1.0/lanes/person-2.md:20), [lanes/person-3.md §Stack](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:19), [docs/10-build-plan.md §6](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:76).  
    **Problem:** Several versions are ranges or absent: `WorkManager 2.11.x`, Hono `4.13.x`, `@hono/zod-openapi 1.6.x`, Modal `1.6.x`; Python dependencies and Android networking/serialization dependencies have no version. Current sources verify WorkManager 2.11.2, Hono 4.13.13, `@hono/zod-openapi` 1.6.3, Modal 1.6.1, OpenAPI Generator 7.26.0, and MCP server 2.3.1. [WorkManager](https://developer.android.com/jetpack/androidx/releases/work), [Hono](https://www.npmjs.com/package/hono), [Hono Zod OpenAPI](https://www.npmjs.com/package/%40hono/zod-openapi), [Modal SDK releases](https://modal.com/docs/sdk/py/releases), [OpenAPI Generator](https://github.com/OpenAPITools/openapi-generator/releases), [MCP SDK releases](https://github.com/modelcontextprotocol/typescript-sdk/releases).  
    **Replace with:** “Pin WorkManager 2.11.2, Hono 4.13.13, `@hono/zod-openapi` 1.6.3, Modal Python SDK 1.6.1, OpenAPI Generator 7.26.0, and `@modelcontextprotocol/server` 2.3.1. Keep the already selected Kotlin 2.4.20, AGP 9.3.1, Gradle 9.5.0, Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5, Glance 1.2.0, and Material 3 1.4.0. Pin the remaining dependencies in the Android and Python lockfiles.” The Android versions are compatible: Kotlin 2.4.20 supports AGP through 9.3.1 and Gradle through 9.7.0; AGP 9.3 requires Gradle 9.5.0. [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [AGP matrix](https://developer.android.com/build/releases/about-agp), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Media3](https://developer.android.com/jetpack/androidx/releases/media3), [Room](https://developer.android.com/jetpack/androidx/releases/room), [Glance](https://developer.android.com/jetpack/androidx/releases/glance).  
    **Version note:** OpenAPI 3.0.4 is a valid deliberate choice for Generator 7.26.0, whose documented OpenAPI 3.1 support is beta. Do not describe 3.0.4 as the latest OpenAPI version. [OpenAPI 3.0.4](https://spec.openapis.org/oas/v3.0.4.html), [Generator compatibility](https://github.com/OpenAPITools/openapi-generator/).

27. **Free-tier limits are incomplete, and alerts do not guarantee a zero-cost run.**  
    **Location:** [docs/03-architecture-and-streaming.md §§4, 7](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:63), [docs/10-build-plan.md §§3, 5](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:42).  
    **Problem:** Supabase limits are partly listed, but its 5 GB egress and 1 GB storage are omitted; R2’s free Class A and B operation quotas are omitted; GitHub Actions limits depend on whether the repository is private. Modal requires a payment method for GPU usage. Current limits include Supabase 500k Edge Function calls, 2M Realtime messages, 200 peak connections, 500 MB database, 5 GB egress, and 1 GB Storage; R2 10 GB-month, 1M Class A, 10M Class B, and free egress; Modal Starter $30 monthly credit and 3 seats; GitHub Free private repositories 2,000 minutes and 500 MB artifact storage. [Supabase billing](https://supabase.com/docs/guides/platform/billing-on-supabase), [R2 pricing](https://developers.cloudflare.com/r2/pricing/), [Modal pricing](https://modal.com/pricing), [Modal GPU requirement](https://modal.com/docs/guide/gpu), [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).  
    **Replace with:** “List each provider’s current quota and the project’s expected usage. Set upload, retention, request, and GPU limits before the demo. Treat Modal credit and budget alerts as usage guidance, not a zero-spend guarantee; stop GPU submissions before credit is exhausted. Record whether GitHub is public or private and enforce its applicable Actions quota.”

28. **Video retention is both unspecified and a team decision.**  
    **Location:** [docs/03-architecture-and-streaming.md §7](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:99), [docs/11-debates.md open decision 5](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:126).  
    **Problem:** The architecture says originals are deleted after `N` days, but leaves `N` blank and asks whether originals or only clips should be kept. That choice affects R2 usage and history across sessions.  
    **Replace with:** “Set a concrete default retention period for originals, result artifacts, and clips. Define the user’s keep/delete controls and the deletion behavior for shared clips and caches. Until the team chooses the period, retention is **UNVERIFIED**.”

29. **The post-C2 simulation and opponent features have no API contract.**  
    **Location:** [docs/06-coaching-intelligence.md §§5–6](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:82), [docs/08-developer-platform.md §3](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:21), [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:51).  
    **Problem:** Person 3 is assigned a simulation endpoint and opponent tendencies, but neither route nor request/response shape appears in the API contract.  
    **Replace with:** “Add `POST /v1/matches/{id}/simulation-runs` with `{moment_id, mode: "read"|"decide", target_zone?}` and a typed response containing score or legality, evidence IDs, and continuation clip IDs. Add `GET /v1/matches/{id}/opponent-scout?opponent_id=...` with typed tiles, counts, and source clip IDs. If these endpoints are deferred, remove them from the implementation plan and describe the simulation as local-only.”

30. **The Claude MCP demo claim outruns the verified host evidence.**  
    **Location:** [docs/08-developer-platform.md §2](/Users/Hetansh/Github/elevate1.0/docs/08-developer-platform.md:17), [docs/09-demo.md §Run sheet](/Users/Hetansh/Github/elevate1.0/docs/09-demo.md:23).  
    **Problem:** D2 marks MCP compatibility with the actual judge or team host **UNVERIFIED**, while the run sheet promises Claude answers. SDK and protocol versions do not prove host compatibility.  
    **Replace with:** “Before claiming Claude integration, run the local stdio server against the actual installed Claude host and verify the answer plus deep link. Until that check passes, mark Claude host compatibility **UNVERIFIED** and show the MCP response using the tested inspector.”

31. **The `app://` deep link has no Android route contract.**  
    **Location:** [lanes/person-3.md §Build order](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:49), [docs/05-app-experience.md §6](/Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md:58).  
    **Problem:** MCP returns `app://m/{match}/r/{rally}?t={ms}`, but the Android app’s intent filter and parameter behavior are not specified.  
    **Replace with:** “Define the app link as `elevate://m/{match_id}/r/{rally_id}?t_ms={t_ms}` and register its Android intent filter. The app opens the rally and seeks to `t_ms`; MCP also returns the tested HTTPS embed URL.”

## Stale-reference check

These are not active-choice contradictions in the final pack:

- Expo, React Native, Skia, and EAS appear as rejected alternatives in `docs/11` D1. An older architecture verdict still recommends Expo, so mark that report historical and superseded by D1.
- Cloudflare Queues and SSE appear as rejected options in D2, not as chosen services.
- `pgvector`, “95%,” km/h speed, live spoken referee calls, auto-suggested court corners, and unqualified “better action” are explicitly excluded, deferred, or gated.
- Remux appears only as a post-C1 upgrade behind a flag.
- Vantage appears only in the rejected-name history.
- Workers AI is still an optional caption service; it is not the API host.

## Missing entirely

- `BLUEPRINT.md` and `docs/00`, both listed as present in the README.
- A committed `fixtures/eval/RESULTS.md` with held-out labels and recorded pass/unmet statuses. It is planned, not present.
- Court-template and ball-profile definitions for each sport claimed as supported.
- A deployed demo API base URL and a tested developer-key flow.
- Exact Python and Android dependency lockfiles, plus one committed model manifest with checkpoint byte sizes, hashes, source URLs, and license status.
- A named, rights-cleared racket clip and its attribution record for rehearsal.
- The implemented player/community features: rankings, opponent matching, meetups, tournaments, court discovery and booking, and coach continuity are **Designed** in the checklist, not in the hackathon build.

## Recommendation

Freeze the interfaces only after resolving the finalizer owner and completion sequence, API base path and auth contracts, event and window schemas, and the score/court pack scope. Build the demonstrated path around one rights-cleared racket clip plus Free Play; report every unmeasured capability as `unknown` or `experimental`.

## Three biggest risks

1. Vision quality is still experimental; candidate coverage and eyeballed crops do not prove match-level tracking or line calls.
2. A suitable racket demo clip, device capture modes, and MCP host compatibility are not yet verified.
3. Modal requires a payment method, while the free-credit and storage quotas do not by themselves prevent overage charges.

## Open questions only the team can answer

- Which rights-cleared racket clip will the team use, and what exactly will the demo claim from it?
- Which badminton ruleset should be the default, and should users select event-specific or casual rules?
- Who owns the Modal account and payment method, and what is the team’s maximum allowed spend?
- What retention period should apply to originals, analysis artifacts, and clips?
- Which post-C2 features will be cut first if the 24-hour schedule slips?