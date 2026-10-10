# R3 verdict

**The four people cannot work in parallel without waiting under the current pack.** The plan’s H0 contracts and fixture promise is not met by the actual delivery schedule: full fixtures, OpenAPI, vectors and device-ready footage arrive between H2 and H8. C1 then asks all four lanes to work together for the first time at H8. Person 4 owns repository setup, contracts, rules, footage, evaluation, embed, academy page and demo, while three interfaces still have ambiguous owners. [Build plan](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:20) [Contracts](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:161) [Person 4 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-4.md:15)

**The plan is salvageable if the team makes the account, scope and interface decisions before coding, seeds a small fixture at H0, and treats H8 as a single vertical slice.** H14 and H19 should add platform reads and demo polish only after that slice works. The exact H8 real-engine path is high risk from a cold start; preflight the whole path by H4.

I reviewed the build pack and supporting findings read-only. **No files were changed and no tests were run.** The README directs readers to `BLUEPRINT.md`, but that file is absent from the pack inventory. Also, the final decision log says no Cloudflare Worker, while the Person 3 brief and architecture doc still keep it as a fallback. [README](/Users/Hetansh/Github/elevate1.0/README.md:8) [Decision log](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:26) [Person 3 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:18) [Architecture](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:61)

## Evidence status and current technical checks

The following version and service details were checked against live sources on 2026-10-10. Estimates and unresolved measurements are listed separately.

| Area | Verified | Consequence for the plan |
|---|---|---|
| Android build | The pinned Kotlin 2.4.20, AGP 9.3.1, Gradle 9.5.0 and JDK 17 combination is listed as compatible. Kotlin 2.4.21 has since released, but the frozen 2.4.20 pin is the compatibility-table entry for this AGP version. The Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5 and Glance 1.2.0 are current pins in the pack. [Kotlin releases](https://kotlinlang.org/docs/releases.html) [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) [AGP 9.3 notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) [Compose BOM](https://developer.android.com/develop/ui/compose/bom) [Media3](https://developer.android.com/jetpack/androidx/releases/media3) [Room](https://developer.android.com/jetpack/androidx/releases/room) [Glance](https://developer.android.com/jetpack/androidx/releases/glance) | Keep the frozen build pins for the event. `WorkManager 2.11.x` is floating and stale: stable 2.12.0 is listed, and its default minimum SDK is 24. Since deferred retry is not core, remove WorkManager from the first build or pin and verify its minimum SDK explicitly. [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| Media3 overlay | `ExoPlayer.setVideoEffects` exists. The `media3-effect` dependency must be added explicitly, and the API requires `@OptIn(UnstableApi::class)`. Media3’s overlay API can use presentation timestamps. [ExoPlayer reference](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer) [TextureOverlay reference](https://developer.android.com/reference/androidx/media3/effect/TextureOverlay) | Person 2’s overlay spike is a good H2 gate. Test timestamp alignment, scrubbing and slow playback on both phones; the API’s existence does not prove the implementation works on those devices. |
| API and generators | Hono 4.13.7 and `@hono/zod-openapi` 1.6.3 are current package versions. OpenAPI Generator 7.26.0 released on Oct 6. MCP TypeScript SDK 2.3.1 released on Oct 5. The pack’s OpenAPI 3.0.4 format is valid but is not the latest published specification; the OpenAPI site lists 3.2.1 and 3.2.0. [Hono](https://www.npmjs.com/package/hono) [`@hono/zod-openapi`](https://www.npmjs.com/package/%40hono/zod-openapi) [OpenAPI Generator releases](https://github.com/OpenAPITools/openapi-generator/releases) [MCP SDK releases](https://github.com/modelcontextprotocol/typescript-sdk/releases) [OpenAPI specifications](https://spec.openapis.org/oas/) | Pin exact package patches in the lockfile. Keep OAS 3.0.4 if it is the deliberate generator-compatible target, but label that as a compatibility choice rather than “latest.” Verify the generated TS client against the actual Deno routes before counting the SDK deliverable as passed. |
| Python video stack | FFmpeg 9.0.2 and PyAV 19.0.1 are current. PyAV 19 requires Python 3.12; the Person 1 brief says Python 3.11 with FFmpeg 9 via PyAV. [FFmpeg releases](https://ffmpeg.org/download.html) [PyAV](https://pypi.org/project/av/) | This is a concrete install blocker. Pin Python 3.12 + PyAV 19.0.1 + FFmpeg 9.0.2, then verify the Modal image builds. Compatibility of that exact combination with the chosen CUDA PyTorch image is **UNVERIFIED** until that build succeeds. |
| Supabase | Free includes 500 MB database, 1 GB file storage, 5 GB egress, 500,000 Edge Function invocations and 2 million Realtime messages. Free projects may pause after seven days of low activity. Edge Functions have a 2-second CPU limit and 150-second wall-clock limit. [Supabase billing](https://supabase.com/docs/guides/platform/billing-on-supabase) [Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing) [Edge Function limits](https://supabase.com/docs/guides/troubleshooting/edge-function-cpu-limits) | Keep video out of Edge Functions. Use Supabase for signing/control, metadata, durable events and reads; upload bytes directly to R2 and do the vision job on Modal. Check the project’s status before the event. |
| Cloudflare R2 | Standard storage includes 10 GB-month, 1 million Class A requests and 10 million Class B requests; internet egress is free. R2’s S3 API supports multipart operations, and parts must be at least 5 MiB except the last part. [R2 pricing](https://developers.cloudflare.com/r2/pricing/) [R2 multipart upload](https://developers.cloudflare.com/r2/objects/upload-objects/) [R2 S3 API](https://developers.cloudflare.com/r2/api/s3/api/) | The chosen 8 MiB parts are valid. However, the presigned URL guide documents GET, HEAD, PUT and DELETE, not the exact signed `UploadPart` query flow this design needs. Whether the Supabase signer and Android upload work together is **UNVERIFIED** until the planned H2 multipart probe passes. [R2 presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/) |
| Modal | Starter lists $30 monthly compute credit and three seats. GPU use requires a payment method. Base rates list T4 at $0.000164/sec and L4 at $0.000222/sec; broad `ap` region has a 1.15 multiplier. These rates exclude some CPU, memory, startup and other costs. Modal Python SDK 1.6.1 released Oct 3. `Function.spawn()` is the documented asynchronous invocation. [Pricing](https://modal.com/pricing) [GPU requirements](https://modal.com/docs/guide/gpu) [Region selection](https://modal.com/docs/guide/region-selection) [SDK release notes](https://modal.com/docs/sdk/py/releases) [Invocation methods](https://modal.com/docs/guide/function-invocation-methods) | This is not guaranteed to stay free: a card is required, the workspace has only three seats for four people, and usage beyond the credit can be billed. No verified zero-overage stop is documented in the plan. Name one operator, agree a maximum spend, and test the account before relying on it. Do not share its login or deploy token among the team. |
| Camera and rules | Samsung lists the Galaxy A55’s HD slow-motion mode as 240 fps. This verifies the product-family spec, not the exact Indian handset’s available mode, timestamps or recording quality. [Samsung A55 specifications](https://www.samsung.com/levant/smartphones/galaxy-a/galaxy-a55-5g-awesome-lemon-256gb-sm-a556ezywmea/) A badminton federation source reports BWF’s 3×15 change taking effect Jan 4, 2027. [Spanish Badminton Federation](https://www.badminton.es/news/163151/La-Asamblea-General-de-la-BWF-aprueba-el-nuevo-sistema-de-puntuacion-315) | Test slow motion on the actual A55 at H0. For a 2026 match clip, use a versioned 3×21 profile; retain 3×15 as a later ruleset. The team still needs to choose the default for casual practice. |
| Agent access and CI | Anthropic lists Claude Code for paid plans or API billing, not as a guaranteed free-plan entitlement. Codex access and limits depend on the user’s ChatGPT plan. GitHub Free includes 2,000 Actions minutes/month for private repositories. [Claude Code setup](https://docs.anthropic.com/en/docs/claude-code/getting-started) [Anthropic pricing](https://www.anthropic.com/pricing) [Codex plan access](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan) [GitHub pricing](https://github.com/pricing) | Each person’s existing plan and remaining usage are **UNVERIFIED**. Confirm them at H0; do not assume four Claude Code sessions are free. Keep local builds as the main path and Actions as a checked backup. |

**Measured or estimated, not verified as production performance:**

- The team’s WASB probe reports 51% candidate coverage and about 85% visually judged precision over only 16 crops. TrackNetV3 reports 69% coverage and about 50% visually judged precision. These are not ground-truth accuracy results. The WASB checkpoint’s redistribution terms are **UNVERIFIED**. [README probe summary](/Users/Hetansh/Github/elevate1.0/README.md:31) [D3 engine evidence](/Users/Hetansh/Github/elevate1.0/research/out/D3-vision-engine.md:44)
- D-FINE-S’s 41.8 MB checkpoint, digest and Apache-2.0 license are recorded. Its cited 3.49 ms T4 TensorRT number is detector inference, not a Modal end-to-end match result. Court-view player accuracy and cloud throughput are **UNVERIFIED**. [D3 engine evidence](/Users/Hetansh/Github/elevate1.0/research/out/D3-vision-engine.md:31)
- TAPIR’s checkpoint is listed at 127 MB, but its cited 17 FPS is on a Quadro RTX 4000, not the planned Modal T4. TrackNetV3’s exact checkpoint bundle size is **UNVERIFIED**. [D3 engine evidence](/Users/Hetansh/Github/elevate1.0/research/out/D3-vision-engine.md:44)
- The pack’s 10–15 second / 40 second upload timing is ideal arithmetic or a regional average, not a measurement on the demo phones and venue network. Actual upload time, Modal startup and end-to-end analysis time are **UNVERIFIED**. [Architecture timing](/Users/Hetansh/Github/elevate1.0/docs/03-architecture-and-streaming.md:38)
- The A55 240 fps mode is **verified at spec level**. Actual capture mode, usable file metadata and ground-truth suitability on the team’s unit remain **UNVERIFIED** until tested.

## First eight hours, by person

These simulations follow the lane order and dependencies as written. Actual account access and device setup are unknown, so those waits are conditional. The fixes below remove the wait by assigning an owner, providing an early stub or narrowing the deliverable.

### Person 1: vision engine

The lane stacks calibration, two player/ball models, audio cues, contacts, landings, scoring, review estimates, clips and free-play tracking into the first fourteen hours. [Person 1 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-1.md:19)

| Hour | Planned work | Block and exact fix |
|---|---|---|
| H0 | Modal app and image setup. | **BLOCKED** if the Modal owner, card and spend limit are unresolved. D11 still lists the owner as open while the build plan assigns P1 an account and shared token. Name P1 as sole operator, agree the spend cap and verify the account before the event. [Decision log](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:119) |
| H1 | Build image and CLI. | **BLOCKED** by Python 3.11 + PyAV 19 + FFmpeg 9 mismatch. Pin Python 3.12, PyAV 19.0.1 and FFmpeg 9.0.2, then build the image first. If CUDA dependencies fail, use FFmpeg CLI for this sprint and stop trying multiple media stacks. |
| H2 | Load models and process a clip. | **BLOCKED** by model selection waiting for P4 evaluation due at H8, and by generated schemas/fixtures due from P4 at H2. Choose TrackNetV3 for the badminton demo at H0, label it Experimental, and start with a small `window_result.v1` fixture at H0. Do not use WASB weights in the demo until their terms are verified. [Person 1 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-1.md:32) |
| H3 | Read the original from R2 and exercise submit. | **BLOCKED** if P3 has not shipped the upload-complete and analysis-submit routes. P3 publishes route signatures and a local fake response by H1; P1 develops against that contract until the H2 multipart probe passes. |
| H4 | Run a Modal job and emit window output. | **BLOCKED** by the undefined credential and write path between Modal and Postgres. P3 owns the event append endpoint and event sequence; P1 posts to it using a scoped secret. No direct database writes from P1. |
| H5 | Add event and final match output. | **BLOCKED** by lane wording that lets P1 either call a Python rules mirror or P3’s function. Delete the Python mirror option. P4 owns the TypeScript rules implementation; P3 owns the API operation and persistence. P1 produces validated engine output only. |
| H6 | Finalize match record and clips. | **BLOCKED** until `ingest_match` is a single declared API/SQL boundary. P3 owns `POST /internal/analyses/{id}/finalize` and SQL persistence; P1 submits `match.v1` and clip artifacts. |
| H7 | Prepare C1. | **BLOCKED** if P2 and P3 first see the real output at H8. Reserve an integration slot at H4 using one fixed clip and one-window output. If it fails, fix the vertical slice before adding contacts, metrics or TAPIR. |

### Person 2: native Android app

The first two hours include command-line Android setup, builds on both phones, gallery playback and the core timestamped overlay spike. [Person 2 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-2.md:19)

| Hour | Planned work | Block and exact fix |
|---|---|---|
| H0 | Install JDK and Android command-line SDK. | **BLOCKED** if downloads, SDK licenses or Gradle resolution begin at event start. Preinstall JDK 17, platform/build tools 36 and platform-tools; verify `adb devices` before H0. |
| H1 | Set up Gradle project and install. | **BLOCKED** by root ownership ambiguity: P4 creates the monorepo while P2 owns `android/`. Assign P2 `settings.gradle.kts`, wrapper and Android version catalog. P4 owns pnpm/uv workspace and CI, not Android Gradle files. |
| H2 | Run the overlay spike on both phones. | **BLOCKED** if it waits for P4’s H2 fixture or both devices are filming. Check in one timestamped trail fixture at H0. Reserve both phones for P2 through H2; schedule P4’s filming after that. |
| H3 | Build repository and Add Match flows on fixtures. | **BLOCKED** if `/v1/openapi.json` arrives at P3’s H2 milestone without a temporary API response shape. Define the `ApiSource` signatures at H0, with a local fake; keep the UI usable via `FixtureSource`. |
| H4 | Timestamped Media3 overlay. | No cross-person wait if the `media3-effect` dependency and `@OptIn(UnstableApi::class)` are included at H0. The device behavior remains a technical risk; test 1x, 0.25x and seek on both phones before adding screens. |
| H5 | Upload and live analysis UI. | **BLOCKED** if P3 has not returned stable multipart and event-cursor shapes. Use a local `ApiSource` fake until P3 passes the multipart probe; do not invent a percent-complete value. |
| H6 | Result, review and match screens. | **BLOCKED** if Person 1’s output schema or event names change at the H8 integration. Freeze the minimal `window_result.v1`, event and `match.v1` fixture at H1. Build one screen against them. |
| H7 | C1 APK. | **BLOCKED** if the only build is the H8 C1 run. Reserve P2’s build/device slot at H4 and do an install smoke test with the fake API before attaching Modal. Defer widgets, export, voice and simulation. |

### Person 3: API, storage, SDK and MCP

The lane assigns P3 more than twenty tables, RLS, multipart, event persistence, match assembly, insights, history, SDK, MCP and later simulation. [Person 3 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:26)

| Hour | Planned work | Block and exact fix |
|---|---|---|
| H0 | Supabase project, R2 bucket and credentials. | **BLOCKED** if projects, access or secrets are not already available. Provision the Supabase Mumbai project and R2 bucket before the event, then verify project status, object access and secret ownership at H0. |
| H1 | Build signer and multipart probe. | **BLOCKED** by uncertainty about the exact presigned multipart operation. Run the 60 MB curl probe first, including create, signed part PUTs, complete and HEAD. Keep file bytes off the Edge Function because of its CPU/time limits. |
| H2 | Ship OpenAPI routes and stub. | **BLOCKED** if P4 has not provided route schemas or P1 has not declared its submit interface. Freeze route names and request/response JSON at H0; P3 can return a fake `StubAnalysis` result while Modal is unavailable. |
| H3 | Event persistence and broadcast. | **BLOCKED** by P1’s instruction to insert rows and broadcast while P3 owns `append_event`. P3 must be the only writer: one SQL transaction assigns `seq`, and P3 sends the wake-up hint. P1 calls P3’s authenticated internal endpoint. [Person 1 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-1.md:20) [Person 3 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-3.md:31) |
| H4 | Attach real Modal submit. | **BLOCKED** if P1’s private endpoint URL and secret are not provided. P1 owns the Modal endpoint; P3 owns the caller. Put a scoped token in the server secret store and pass only the endpoint contract, not shared login credentials. |
| H5 | Migrations and RLS. | **BLOCKED by scope** if all twenty-plus listed tables are treated as H2 prerequisites and must match unfinished P4 schemas. Start with the minimum tables: sessions, uploads, analyses, analysis_events, matches and artifacts. Add review/correction rows only if the core path passes. |
| H6 | Comparison/history/insights and SDK/MCP. | **BLOCKED** on real completed records and a rights-cleared comparison clip. Defer insights and comparisons; build one read route, then one TS SDK call and one local read-only MCP tool after C1. |
| H7 | C1 integration. | **BLOCKED** if H8 is the first multipart, event-cursor or Modal test. H2 must prove upload and stub end-to-end. H4 must exercise one real model job before the checkpoint. |

### Person 4: contracts, rules, footage, evaluation and demo

P4’s role is the largest dependency source: it promises “nobody waits,” while delivering full fixtures at H2, six rule sets and vectors at H2–6, footage and labels at H2–10, then evaluation, embed, academy page and rehearsals. [Person 4 brief](/Users/Hetansh/Github/elevate1.0/lanes/person-4.md:18)

| Hour | Planned work | Block and exact fix |
|---|---|---|
| H0 | Start monorepo, contracts and CI. | **BLOCKED** by open decisions: Modal owner, footage rights, badminton rules and cloud retention. Decide these at H0. Use `Elevate Rally` as a working name or omit the name from the demo. [Decision log](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:119) |
| H1 | Workspace/CI, schemas, fixtures. | **BLOCKED** by P2’s Android Gradle files and P3’s package/migration needs if P4 owns all root setup. Keep P4 on root workspace, `AGENTS.md` and CI; explicitly hand Gradle files to P2 and migrations to P3. |
| H2 | Deliver contracts and fixtures 1–5. | **BLOCKED** because example data in the prose contract is not a set of machine-readable schemas and runtime fixtures. Deliver only four small files first: `window_result.v1`, one event JSONL, one `match.v1`, and one trail fixture. Full fixture set is optional. |
| H3 | Implement six score rule sets. | **BLOCKED** if P4 waits for the full vectors and multiple sports’ edge cases. Implement BWF 3×21 `applyPoint`, confirm and undo only; generate golden vectors immediately. Defer 3×15, ITTF and tennis scoring. |
| H4 | Film and test on both phones. | **BLOCKED** by device contention with P2 and by the A55 reference assumption. Use the H0 reservation. Test the real A55’s HD 240 fps mode; if unusable, remove the automatic line-call demo and keep human review/confirmation. |
| H5 | Label 200 positions, 30 hits, 20 landings and 10 rallies. | **BLOCKED** by the lack of match footage at the venue and an unbounded labeling task. Select one existing rights-checked badminton clip and label a small holdout for internal diagnostics. Do not use that sample to claim match-level accuracy. |
| H6 | Finish evaluation and prepare embed/academy page. | **BLOCKED** because real engine output is not due until H8. Build the evaluation script around the frozen schema now; run it only after P1 emits output. Drop the academy page from the critical path. |
| H7 | Seed demo and prepare C1. | **BLOCKED** because P4 is named as demo seeder but P3 owns database persistence and P1 owns clip cutting. P4 supplies source manifest, attribution and consent; P3 owns a one-command `seed_demo_match`; P1 owns cutting. |

## Ownership corrections

Use the following ownership table before the first commit. It separates construction of results from persistence and presentation.

| File/interface/resource | Single owner | Other lane’s interface | Correction |
|---|---|---|---|
| Root workspace, `AGENTS.md`, CI | P4 | P1–P3 contribute lane checks | P4 owns pnpm/uv workspace and CI. Do not include Android Gradle root files. |
| `android/settings.gradle.kts`, Gradle wrapper, Android version catalog | P2 | P4 CI invokes Android build | Add P2 as explicit owner; the brief currently says only `android/` while P4 claims repo setup. |
| `packages/contracts/` and `docs/contracts.md` | P4 | P1/P2/P3 consume | P4 owns canonical JSON schemas, examples and fixture manifests. Keep the H0 contract small and machine-readable. |
| `/v1` routes, operation IDs, auth/errors, `/v1/openapi.json` | P3 | P4 schemas; P2/SDK consume | P3 owns routes and OpenAPI generation. Pin `3.0.4` only if the generator path is tested. |
| `packages/rules/` TypeScript | P4 | P3 calls it; P2 ports it | P4 owns the canonical implementation and golden vectors. P2 owns the Kotlin port. Remove P1’s optional Python mirror. [Contracts](/Users/Hetansh/Github/elevate1.0/docs/contracts.md:161) |
| `append_event` SQL and Broadcast | P3 | P1 sends event payload to internal route | P3 alone assigns `seq`, stores the durable row and sends a `{analysis_id, latest_seq}` hint. Do not have P1 write directly to Postgres. |
| `match.v1` construction and persistence | P1 constructs; P3 persists | P2 reads API response | Replace “P3 `ingest_match` SQL function or documented tables” with one P3-owned finalize endpoint and one SQL function. |
| Modal submit endpoint | P1 | P3 calls it from Hono | P1 owns deployment and its secret. P3 owns call/retry/idempotency. Do not share the Modal account token through CI. |
| R2 multipart signing/completion | P3 | P2 uploads direct; P1 accesses stored objects | P3 owns signer and object keys; P2 owns client upload state; P1 uses scoped Modal secrets for original-read/result-write. |
| Video source and clip cutting | P4 owns rights, consent, attribution and source manifest. P1 owns server-side cut operation. | P3 stores artifact metadata; P2 plays it | The clip cutting owner is already named P1, but clip selection and metadata boundaries need this split. [Real clips](/Users/Hetansh/Github/elevate1.0/docs/12-real-clips.md:29) |
| Demo match seeding | P3 owns DB seed command | P4 supplies rights-cleared media and seed JSON | The plan names P4 as demo seeder but P3 owns Postgres. Put `seed_demo_match` under P3; P4 reviews provenance. [Real clips](/Users/Hetansh/Github/elevate1.0/docs/12-real-clips.md:40) |
| Evaluation | P4 owns labels, script and results report | P1 supplies immutable engine output and manifest | Separate evaluation data from the code that generates detections. |
| Integration runbooks | P4 owns acceptance scripts | Each lane owns unit checks in its folder | Keep P4 as integration captain only after dropping the academy page, broad rules portfolio and large labeling task. |

## Workload and 24-hour realism

**P4 is overloaded. P1, P2 and P3 also have too much scope for one dependable end-to-end path.** The plan’s hours 2–8 assign P1 several vision modules, P2 multiple screens, P3 a full API and P4 rules, evaluation labels and filming, then require all of them to integrate by H8. [Build schedule](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:49)

| Person | Keep in the core path | Defer or remove from the 24-hour commitment |
|---|---|---|
| P1 | One real static badminton clip through PTS-aware decode, TrackNetV3 candidate trail, model manifest, one validated result artifact. Keep all outputs Experimental until evaluation. | Defer WASB until weight terms are resolved; defer TAPIR, full D-FINE player metrics, audio/contact/landing inference, automatic referee, multi-window 50-minute claims and broad clip library. |
| P2 | Gallery picker, instant local playback, four calibration taps if needed, truthful upload/job stages, timestamped overlay, one evidence/result view, confirm/correct score, cached history. | Defer widgets, search, Today feed, notifications, export, voice, simulation, segment remux and synced dual-player comparison. The decision log already places many daily-life features after C2. [Decision log](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:109) |
| P3 | Auth/session, direct R2 upload path, analysis creation, durable event cursor, match read and artifact URLs. After C1, add one TS SDK and one read-only MCP call to the same record. | Defer the 20-plus table schema, insights job, player trends, similar-moment search, LLM captions, opponent analysis, simulation, Python SDK, quotas and webhooks. |
| P4 | Minimal contracts and fixtures by H1, one versioned BWF 3×21 ruleset, rights/consent manifest, integration acceptance sheet and rehearsal. | Defer six rulesets, 200-point evaluation, academy page, reference library automation and full embed. Keep the embed as an optional H14–H19 extra only if C1 and C2 pass. |

The informal college clip should remain a separate **assisted free-play ingestion demo**. The local probe found zero sport-tracker hits on the catch clip; it does not prove cross-sport model generalization. [README probes](/Users/Hetansh/Github/elevate1.0/README.md:33) [D6 product evidence](/Users/Hetansh/Github/elevate1.0/research/out/D6-product-moat-demo.md:42) The badminton competition clip in the footage manifest is 30 fps at 848×464 under CC BY-SA 4.0; the ArtLabss tennis broadcast clip is marked research use only. [Footage sources](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md:3)

## Checkpoints and integration scripts

Treat each checkpoint as a test of one working path, not a feature inventory. P4 should own the run sheet and record each criterion as `pass`, `unmet`, `unknown` or `could-not-run`, matching the build plan’s own status vocabulary. [Build plan](/Users/Hetansh/Github/elevate1.0/docs/10-build-plan.md:60)

### H8: one real racket clip through the stack

| Person | Must bring |
|---|---|
| P1 | Deployed Modal image with pinned manifest; TrackNetV3 candidate output for the selected badminton clip; validated `window_result.v1` in R2. |
| P2 | APK installed on both phones; verified 1x, 0.25x and seek overlay spike; one usable rally screen on S23 FE. |
| P3 | Multipart upload and completion; analysis submission; P1 job trigger; `append_event` with ordered `seq`; cursor read; match/artifact retrieval. |
| P4 | Fixed clip source, rights/attribution record, frozen schemas and a one-page pass/fail sheet. |

**H8 script**

1. Select the same short, rights-checked badminton rally clip on the S23 FE. Record source, license and checksum in the run manifest.
2. Create session, upload through R2, complete upload, and check stored object size. The separate H0/H2 multipart probe must already have tested a larger multi-part object.
3. Create an analysis with an idempotency key. Confirm the API invokes the real Modal job, not `StubAnalysis`.
4. Confirm the worker reads the uploaded original, writes schema-valid output and model hashes, and calls P3’s event endpoint. Confirm `seq` is strictly ordered and that a cursor fetch recovers every event after a missed hint.
5. Fetch `match.v1`; compare its artifact and event references with the stored rows.
6. Render the trail over the original video on the S23 FE at 1x, 0.25x and after seeking. Verify the trail uses source presentation time, not a timer.
7. Mark the trail `Experimental`. If any dependency fails, record the criterion as `unmet` or `could-not-run`; use the fixture only to keep app development moving.

**H8 risk:** This is the first shared real integration and depends on three live systems. If the entire path has not passed once by H4, stop adding features and finish the slice.

### H14: fresh upload, durable record, developer read

| Person | Must bring |
|---|---|
| P1 | Stable final result and manifest for one badminton analysis; no new model families. |
| P2 | Fresh phone upload, result/review flow, confirmed score and offline cached read. |
| P3 | Same match retrievable through API, one TS SDK call and one read-only MCP tool. |
| P4 | Evaluation report with sample counts and explicit limits; provenance for both records and clips. |

**H14 script**

1. On a clean app session, import an unprocessed local clip and upload it. For the informal venue clip, call it practice/free-play; a TAPIR trail is shown only if the spike passed and the user seeded the point.
2. Display truthful upload bytes and server job state. Verify retry/resume by interrupting connectivity once, then confirm the same upload completes without duplicating the analysis.
3. Confirm or correct one score event. Verify the confirmation is stored separately from the model proposal.
4. Turn on airplane mode and reopen the cached record, video and overlay. Then restore connectivity.
5. Fetch the same `match_id` from the API, TS SDK and MCP. Compare returned IDs and schema version.
6. Run the comparison screen only if a separate rights-cleared reference clip and comparison record exist. Below the evidence gate, label it `SIMILAR MOMENT`; do not call it a recommendation that worked for the player. [Decision log](/Users/Hetansh/Github/elevate1.0/docs/11-debates.md:75)
7. The requested “history across two matches” is **UNMET** unless two distinct match sources are available. The listed EuroGames rally clips come from the same source video, not documented separate matches. Do not manufacture a second match from a second segment. [Footage sources](/Users/Hetansh/Github/elevate1.0/research/probes/footage/SOURCES.md:5)

### H19: freeze and demo rehearsal

| Person | Must bring |
|---|---|
| P1 | Warmed, known model image; one result from the fixed badminton clip; honestly labelled fallback clip analyzed earlier. |
| P2 | Release APK on both phones; airplane-mode history check; DeX/HDMI or chosen display path rehearsed. |
| P3 | Budget guard and concurrency limit; seed command for approved demo records; API/SDK/MCP read verified. |
| P4 | Capability strip with evidence provenance; rights and consent manifest; final script and fallback cue card. |

**H19 script**

1. Install the release APK cleanly on both phones. Check local gallery playback and one cached result in airplane mode.
2. Rehearse the complete five-minute demo once with the actual display adapter, phone and hotspot. No QR dependency.
3. Run the college corridor upload once. If tracking succeeds, show only its seeded point’s observed pixel trail. If it fails or remains queued, say so and open a separately processed clip labelled `analysed earlier`.
4. Open the real badminton clip and show the racket-sport result separately from the corridor free-play clip. Do not present the corridor result as evidence that badminton tracking generalized.
5. Read the same match record from the app, API, TS SDK and MCP. If the embed is not working by H19, show the API response and remove the embed claim.
6. Inspect the final capability report and ensure no synthetic fixture or cached result is presented as freshly analyzed. The demo pack itself requires this distinction. [Demo run sheet](/Users/Hetansh/Github/elevate1.0/docs/09-demo.md:1)

## Hour-0 checklist

Accounts and decisions must be ready before coding starts. If accounts or toolchains need to be created at the event, the associated lane will wait.

### First 10 minutes: decisions

- Freeze the core path as **badminton replay on one fixed, rights-checked clip**. Keep the corridor capture as a separate assisted free-play demo.
- Choose `bwf_3x21` for a 2026 match record and make the ruleset version explicit. Keep 3×15 as a future profile rather than trying to implement all formats during the sprint.
- Name one Modal operator, settle card use and a hard team spend ceiling, and decide that only the operator deploys.
- Decide the cloud retention default: keep the user’s original in the phone gallery, delete the server original after analysis unless the user opts to retain it, and retain derived clips only with appropriate consent.
- Select the badminton source clip and the person who verifies attribution. Use a team-recorded corridor clip only with participants’ consent.

### By 20 minutes: accounts and access

- GitHub repo access and Actions enabled. Assign P4 root workspace and CI; P2 owns the Android Gradle root.
- Supabase project in Mumbai, verified active, with the needed Edge Function and database permissions.
- Cloudflare R2 bucket and scoped credentials for signing, Modal reads and artifact writes.
- Modal operator account, card, spend agreement and T4 availability checked. Starter’s three-seat limit means the plan must not assume all four need workspace seats. [Modal pricing](https://modal.com/pricing)
- Each person confirms their actual Claude Code or Codex access and usage. No new paid subscription is assumed under the free-only constraint. [Claude Code setup](https://docs.anthropic.com/en/docs/claude-code/getting-started) [Codex plan access](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan)
- One Mac already has JDK 17, Android SDK platform/build tools 36, Gradle wrapper cache and `adb` access to both devices.

### By 30 minutes: smoke tests and shared contract

- Run `./gradlew assembleDebug` and install the APK on both devices.
- Test one actual R2 multipart upload, completion and HEAD check. This specifically checks signed `UploadPart` behavior, which is not established by the presign guide.
- Test the private Modal submit with a small input and one `Function.spawn()` call if the spend decision allows it.
- Film a short A55 slow-motion sample; inspect the resulting file and frame timestamps. Samsung’s 240 fps spec alone does not prove the unit is suitable as ground truth.
- Publish the minimal schemas and exact signatures for session create, multipart init/complete, analysis create, event cursor, window result, match read and finalization. Include auth, idempotency and error forms.
- Put one timestamped overlay fixture, one rights-cleared rally clip manifest and one BWF 3×21 golden-vector file in the shared repo.

## Recommendation

Build the one-clip badminton cloud analysis vertical slice first. Keep the app premium through immediate local playback, a careful timestamped trail, evidence-linked results and reliable history. Use the live college clip to demonstrate upload and assisted tracking only. Keep automatic officiating, cross-sport generalization, trend claims and “worked for you” comparisons behind evidence gates. [D6 product recommendation](/Users/Hetansh/Github/elevate1.0/research/out/D6-product-moat-demo.md:128)

## Three biggest risks

1. **Integration timing:** accounts, schema, fixture and signed multipart upload are not ready at H0; C1 then has no time for a full-stack failure cycle.
2. **Vision quality and honest claims:** current phone probes are narrow, informal footage does not prove racket tracking, and the Modal end-to-end rate is **UNVERIFIED**.
3. **Cost and demo reliability:** Modal requires a card and can exceed its credit; venue connectivity, cold start and exact A55 slow-motion ground truth are **UNVERIFIED**. [Modal pricing](https://modal.com/pricing) [A55 specifications](https://www.samsung.com/levant/smartphones/galaxy-a/galaxy-a55-5g-awesome-lemon-256gb-sm-a556ezywmea/)

## Open questions only the team can answer

1. Who is the single Modal operator, and what exact spend limit will the team accept?
2. Which exact badminton clip is the demo source, and who owns its attribution and rights check?
3. Which two distinct match sources can support a real two-match history demo, if any?
4. If the corridor tracker does not produce a stable path, will the team present upload/status only and label the pre-analyzed fallback?
5. Which developer-platform surfaces are mandatory by H19: API, SDK, MCP, embed, or a smaller verified subset?
6. What cloud retention period and consent wording will the team use for originals and derived clips?