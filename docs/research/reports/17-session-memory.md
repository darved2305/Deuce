# Track 17: History across many sessions

**Verdict:** Use **Supabase Postgres in Mumbai as the canonical history database**, with immutable analysis runs and `pgvector` for optional similarity search. Store videos separately in object storage. Make the coach a query tool over that history: SQL finds facts, similarity search finds candidate moments, and the LLM explains only the evidence it receives.

The product’s strongest moat is a player’s **evidence-linked history**: clips, corrections, confidence, camera quality, and analysis versions connected over time. A generic LLM or vector database is replaceable. That history is not.

Research was checked against live sources on **2026-10-10**. Free quotas and model catalogs can change. The provider limits below describe current published offers, not guaranteed capacity.

## Architecture decisions

| Area | Recommendation |
|---|---|
| Canonical history | Supabase Postgres, pinned to Mumbai `ap-south-1`; use relational tables for sessions, events, corrections, permissions, and metric definitions. |
| Media | Store originals and generated clips as separate objects. Cloudflare R2 currently includes 10 GB-month of standard storage and free internet egress. R2’s Mumbai placement is **UNVERIFIED**. |
| Similarity | Start with structured SQL and filters. Add `pgvector` for handcrafted situation features after exact search is insufficient. Keep the source event and clip IDs in every result. |
| Coach memory | Keep facts in the database. Retrieve through a small set of authenticated tools; send a compact evidence pack to an LLM. |
| Offline | Cache the history feed, summaries, pinned clips, and deterministic search locally. Treat local LLM phrasing as optional. Full video analysis remains cloud based. |
| Comparisons | Compare one metric at a time, only when sport, rules, session type, camera quality, and metric definition are compatible. |
| Developer platform | Expose the same history and retrieval service through versioned REST endpoints, SDKs, and read only MCP tools. |

Supabase Free currently provides two projects, 500 MB database size per project, 1 GB storage, and 5 GB egress. Free projects may pause after a week of inactivity. Supabase lists Mumbai as `ap-south-1`; selecting a region controls primary data placement but does not by itself establish regulatory compliance. [Supabase billing limits](https://supabase.com/docs/guides/platform/billing-on-supabase), [regions](https://supabase.com/docs/guides/platform/regions)

Cloudflare R2’s free allowance is 10 GB-month, one million Class A requests, ten million Class B requests, and free egress. That helps with video playback, but it is a finite allowance, not unlimited archival storage. [R2 pricing](https://developers.cloudflare.com/r2/pricing/)

**Free tiers cannot guarantee indefinite retention of every original video.** The schema can preserve all event history, corrections, and analysis versions, but unlimited media retention needs more storage than the published free allowances promise. Do not silently delete videos when a quota fills. Show usage and let the user decide whether to export, delete, or stop uploading.

## Schema sketch

Separate a **match** from a **session**. A session is one recorded practice or match analysis. A match can exist without video, and multiple sessions can refer to the same match.

| Entity | Important fields and purpose |
|---|---|
| `players` | Account and profile identity. Keep account ID distinct from detected-person identity. |
| `opponents` | Optional player link or private alias. Allow an unknown participant without forcing identity resolution. |
| `sports`, `rulesets` | Versioned sport, singles/doubles mode, court dimensions, and scoring rules. |
| `matches` | Competition, participants, score, outcome, and ruleset version. |
| `sessions` | Owner, `practice` or `match`, sport, optional match, date, venue, capture profile, and processing state. |
| `session_participants` | Person or unknown participant, role, identity confidence, and consent references. |
| `media_assets` | Original, transcode, thumbnail, or moment clip; object key, checksum, duration, and source time range. |
| `analysis_runs` | Session, input checksum, engine and model versions, ruleset version, metric definition version, calibration, status, and parent run. |
| `rallies`, `shots`, `situations`, `moments` | Derived events, each tied to an `analysis_run_id`; moments also link to source timestamps and clip assets. |
| `metric_observations` | Metric key and definition version, value and unit, numerator and denominator, confidence, comparability status, and source event IDs. |
| `user_corrections` | Append only correction with actor, target run and event, old and corrected values, reason, and time. |
| `event_correspondence` | Links an event in an older analysis run to its counterpart in a newer run, where one can be identified. |
| `summary_nodes` | Rally, session, week, month, and season summaries with source IDs, model version, generation time, and content hash. |
| `player_trait_claims` | A versioned claim such as a shot tendency, with confidence, sample count, supporting and counter evidence, validity dates, and status. |
| `participant_consents`, `share_grants` | Participant, purpose, data scope, recipients, grant time, expiry, and revocation. |
| `deletion_jobs` | Tracks deletion across original media, clips, derived data, indexes, summaries, caches, and processor copies. |

**Keep analysis results immutable.** A user correction is an overlay against a specific run. A better engine creates a new run; it does not rewrite the old result. Reconcile claims and summaries against the new run, preserve prior versions, and show when history was reanalysed.

A trait should be a claim supported by evidence, not a mutable paragraph. For example: “Backhand errors increased under wide forehand pressure” should include the relevant sessions, count of observed backhands, counterexamples, confidence, and analysis versions.

## Retrieval and storage options

Structured queries should answer questions like “all my backhand errors in close games.” Similarity search should answer “find moments like this rally.” They serve different purposes.

| Option | Current free limits and useful facts | Fit |
|---|---|---|
| **Supabase Postgres + pgvector** | 500 MB database and 1 GB storage per Free project. Mumbai is available. Upstream `pgvector` is currently **0.8.7**, released 2026-10-01; the extension version available on a newly created Supabase project is **UNVERIFIED** until checked there. [Supabase billing](https://supabase.com/docs/guides/platform/billing-on-supabase), [regions](https://supabase.com/docs/guides/platform/regions), [pgvector changelog](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md) | **Recommended canonical store.** One SQL system can hold events, corrections, permissions, and vectors. Keep video objects out of its database quota. |
| **Cloudflare D1 + Vectorize** | D1 Free currently has 5 million row reads per day, 100,000 writes per day, 500 MB per database, and 5 GB total account storage. Vectorize Free has 5 million stored dimensions and 30 million queried dimensions per month. At 384 dimensions, 5 million stored dimensions is about 13,000 vectors before other usage. Vectorize writes are asynchronous. [D1 pricing and limits](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Vectorize pricing](https://developers.cloudflare.com/vectorize/platform/pricing/) | Good for a Workers first deployment. D1 and Vectorize are separate stores, so use an outbox and reconciliation to handle pending or failed vector updates. |
| **Neon Postgres + pgvector** | The 2026-10-02 free plan update lists 1 GB database storage per project and 100 CU-hours per project per month. Public region information includes Singapore; Mumbai availability for a particular account is **UNVERIFIED**. [Neon free plan update](https://neon.com/blog/neon-free-plan-1-gb-per-project), [regions and latency dashboard](https://neon.com/demos/regional-latency) | Strong Postgres alternative, especially if its project quota fits better. Check the actual project region and sleep behavior before choosing. |
| **Turso vectors** | Free currently lists 100 databases, 5 GB storage, 500 million rows read per month, 10 million rows written, 3 GB sync, and one day of point in time recovery. It includes vector columns and DiskANN search; it lists Mumbai `aws-ap-south-1`. Kotlin Android integration is **UNVERIFIED**. [Turso pricing](https://turso.tech/pricing?frequency=monthly), [vector documentation](https://docs.turso.tech/features/ai-and-embeddings), [regions](https://turso.tech/blog/turso-cloud-new-regions) | Interesting if local first sync becomes a core requirement. Run a small Android and sync spike before committing. |
| **LanceDB OSS** | Python package **0.40.0**, released 2026-10-07. Apache-2.0. OSS has no hosted-provider quota; the team owns its hosting, backups, and API. A current hosted free tier is **UNVERIFIED**. [PyPI release](https://pypi.org/project/lancedb/), [repository](https://github.com/lancedb/lancedb) | Useful for a local or self managed similarity service, not the simplest shared history database for this app. |

There is no comparable published benchmark for these stores using the same dataset, filters, region, network, and cold start state. Phone to Mumbai p50 and p95 latency is **UNVERIFIED**. Measure it on the team’s actual venue network before promising a response time.

### Query flow

1. Parse the question into a typed query. For “all my backhand errors in close games,” show chips such as `player: me`, `stroke: backhand`, `outcome: error`, `score: close game`.
2. Resolve “close game” using that sport’s versioned scoring rules. Let the user see or edit the interpretation.
3. Run SQL filters and aggregation. SQL calculates the count and rate; the LLM does not calculate statistics from prose.
4. For “moments like this,” use situation similarity only after applying owner, participant consent, sport, date, and run filters. Join returned IDs back to the authorized SQL rows before showing them.
5. Return moment IDs, clip timestamps, confidence, and run version with the result.

Start with handcrafted situation features, for example court-relative position, shot side, rally phase, score state, movement direction, and shot outcome. Court-relative features require valid court calibration. They are directly interpretable and do not require a venue or sport specific training step. Add pose or CLIP/SigLIP frame embeddings only when a measured retrieval need justifies their compute and storage cost. A similarity score is a ranking signal, not evidence that two situations are equivalent.

## LLM coach memory and guardrails

Recent agent memory work explores linked, evolving notes, while memory evaluations test accurate retrieval, updates, long range reasoning, and selective forgetting. Those are useful design prompts, not a guarantee that a particular memory framework will be reliable for sports coaching. [A-MEM](https://arxiv.org/abs/2502.12110), [MemoryAgentBench](https://arxiv.org/abs/2507.05257)

Use a small authenticated tool surface:

```text
find_sessions(filters, cursor)
search_moments(filters, similarity_query, cursor)
get_metric_trend(metric, date_range, comparability_policy)
compare_sessions(session_a, session_b, metric_ids)
get_player_claims(date_range, sport, status)
get_moment(moment_id)
```

These tools should call parameterized server functions. Do not let the LLM issue arbitrary SQL, alter a correction, or write a player trait. Enforce account, participant, consent, sport, date, and analysis version scope in retrieval.

Return an evidence pack such as:

```json
{
  "claim": "Backhand errors increased under wide forehand pressure",
  "evidence": [
    {
      "session_id": "session_123",
      "moment_id": "moment_456",
      "clip_id": "clip_789",
      "source_timestamp_ms": 84200,
      "analysis_run_id": "run_12",
      "metric_definition": "backhand_error_rate_v2",
      "numerator": 3,
      "denominator": 14,
      "confidence": 0.82,
      "comparability": "comparable"
    }
  ]
}
```

Every factual coach statement should cite a session, rally, moment, or clip ID in the app. A tap should open that evidence. If retrieval returns no evidence, say “I do not have enough comparable evidence.” For “like your smash on 3 Oct,” query the date and event type, then offer the matching clips if there is more than one.

Generate summaries after analysis finishes, outside the upload and playback path:

```text
shot/rally events -> rally summary -> session summary -> week/month summary -> season summary
```

Each summary stores source IDs, source run versions, generation time, and model or prompt version. Treat it as a cache that can be rebuilt, not a fact store. Player model entries should be evidence linked claims with support, counter evidence, confidence, and validity dates. Keep user reported preferences separate from engine inferred patterns.

### LLM API choices and free limits

| Provider | Current API or model | Current free allowance | Data and reliability implications |
|---|---|---|---|
| **Cloudflare Workers AI** | REST API and current model `@cf/openai/gpt-oss-20b`; function calling is documented as Beta. | 10,000 Neurons per day, reset at 00:00 UTC. The pricing page lists 18,182 input and 27,273 output Neurons per million tokens for that model. On the Free plan, operations fail after the daily allocation. [Pricing and model rates](https://developers.cloudflare.com/workers-ai/platform/pricing/), [function calling](https://developers.cloudflare.com/workers-ai/features/function-calling/) | Cloudflare says it does not use content to train AI models or improve services without explicit consent. India specific inference or storage residency is **UNVERIFIED**. [Data usage](https://developers.cloudflare.com/workers-ai/platform/data-usage/) |
| **Groq** | OpenAI compatible endpoint `https://api.groq.com/openai/v1`; candidate `openai/gpt-oss-20b`. | Exact free limits for the team’s organization are **UNVERIFIED**. Check the live account limits before the event. [Rate limits](https://console.groq.com/docs/rate-limits), [API compatibility](https://console.groq.com/docs/openai) | Groq says inference inputs and outputs are not retained by default, with temporary reliability or abuse logging possible. [Data policy](https://console.groq.com/docs/your-data) |
| **Gemini API** | Current model `gemini-3.8-flash`; Interactions API supports `store=false`. [Models](https://ai.google.dev/gemini-api/docs/models), [Interactions API](https://ai.google.dev/gemini-api/docs/interactions-overview) | Free input and output tokens are available for eligible models, but exact limits depend on project, tier, and model. Google says capacity may vary and limits are not guaranteed. [Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) | On unpaid service, Google may use submitted content and outputs to improve products, and human reviewers may process them. Do not send raw video, names, faces, or identifiable private stats through the free tier. [Gemini API terms](https://ai.google.dev/gemini-api/terms) |
| **OpenRouter** | OpenAI compatible endpoint; `openrouter/free` dynamically selects a free model. | Current Free plan lists 50 requests per day. [Pricing](https://openrouter.ai/pricing/) | The free plan lacks data policy based routing. Providers can have different data practices, and model routing can change. Keep it for synthetic demos or experimentation. [Routing and privacy options](https://openrouter.ai/docs/guides/features/sovereign-ai) |
| **Local fallback** | Optional `Qwen3.5-0.8B-Q4_0.gguf` with `llama.cpp`. The model card lists Apache-2.0. A live Hub metadata dry run reported about 563 MB for that file. [Model files and license](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF), [Android build guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) | No API charge. | Speed and memory use on the Galaxy S23 FE and Galaxy A55 are **UNVERIFIED**. Keep it out of the core path. Local deterministic search and cached summaries are the safer offline fallback. |

**Recommendation for model usage:** Keep the provider behind an adapter and start with Cloudflare Workers AI or Groq only after a team account probe confirms free quota and the privacy terms fit. Send pseudonymous, minimized evidence fields, never video. Do not block the analysis result on LLM availability. Gemini free is a poor fit for private user history because of its unpaid data terms.

## Honest comparisons across sessions

A court homography can map points between image coordinates and a planar court. It does not recover the 3D path or speed of an airborne ball or shuttle. [OpenCV homography documentation](https://docs.opencv.org/5.0/tutorials/features/homography/homography.html) Single camera badminton research also reports reliance on fixed broadcast style views and difficulties with occlusion and front court shots. [MonoTrack](https://arxiv.org/abs/2204.01899)

Store a capture profile and quality record for each session:

- Resolution, frame rate, orientation, crop or zoom, and fixed or handheld camera.
- Camera motion estimate and visible court fraction.
- Court template, transform, calibration residual, and calibration coverage.
- Detection coverage, occlusion, event confidence, and analysis version.

Use comparability per metric rather than assigning one grade to a whole session. The labels below are a proposed product convention, not a published standard.

| Label | Example use |
|---|---|
| **A: calibrated** | Stable view with enough court visible for reliable calibration. May support court position and movement metrics. Speed still needs frame timing and suitable geometry. |
| **B: directional** | Partial court or some camera movement. Show selected relative patterns and event counts when detection quality supports them; avoid absolute distance or speed claims. |
| **C: qualitative** | Handheld or no court calibration. Show moments, clip evidence, and cautious coaching observations. Do not claim numeric court coverage, personal bests, or precise speed. |

For a percentage such as backhand error rate, show `confirmed backhand errors / observable backhand attempts`, the sample count, and confidence. Compare only the same sport, ruleset, singles or doubles mode, practice or match type, metric definition, and compatible capture quality. Mark trend lines when engine versions differ; preserve the old run and explain any reanalysis.

For the college clips without a real court, use them to show clip selection, moment references, qualitative coaching, and the “beyond racket sports” story. They should not create quantitative personal bests.

## History UX

1. **Session timeline:** Feed cards show date, sport, practice or match, opponent if known, result, analysis status, and a visual moment thumbnail. Keep the first screen scannable.
2. **Session detail:** Open with the strongest relevant clip, then recommendations and supporting moments. Put the court map and dense numbers behind a drill in.
3. **History search:** Show the parsed filters and result count before the user opens results. “All my backhand errors in close games” should produce tappable chips and clips, not an ungrounded paragraph.
4. **Comparison view:** Preflight sport, mode, ruleset, camera quality, and engine version. Draw only eligible metrics; label the rest “directional” or “not comparable.”
5. **Better action:** Pair the user’s moment with their own stronger example, a reference clip with sharing permission, or an animated court when no suitable clip exists.
6. **Collections:** Let users pin and group moments. Collections inherit the clip’s access rules.
7. **Reminders:** Make “3 unreviewed sessions” and “one year ago” opt in. Keep lock screen notifications generic. Do not send progress reminders from low quality or incomparable sessions.
8. **Offline behavior:** Cache the timeline, summaries, and pinned moments. Queue a new upload for retry. Show the last sync time and distinguish “queued,” “processing,” and “ready” so cloud work does not block browsing.

## Privacy, consent, and deletion

The Indian DPDP Act defines consent as free, specific, informed, unconditional, and unambiguous. Its commencement is phased: as of 2026-10-10, the notification schedules the main consent and rights provisions for 18 months after publication on 2025-11-13, which is 2027-05-13. The one year provisions are scheduled for 2026-11-13. Whether all provisions apply to this product or any individual upload is a legal applicability question and is **UNVERIFIED**. Design for clear consent now and obtain appropriate advice before treating legal applicability as settled. [Official Act](https://www.meity.gov.in/static/uploads/2024/02/Digital-Personal-Data-Protection-Act-2023.pdf), [commencement notification](https://www.meity.gov.in/static/uploads/2025/11/c56ceae6c383460ca69577428d36828b.pdf)

Treat identifiable video and person linked movement as personal data for product design. That is an inference from the Act’s broad definition, not a legal conclusion about this app.

- Default sessions to private. The uploader’s consent does not stand in for other identifiable players.
- Request separate scopes for analysis, coach sharing, reference library use, and public sharing.
- Let a participant remain unknown and private. Blur non consenting people in shared exports by default.
- Make raw video, selected clips, and stats only distinct sharing choices. Show recipient, expiry, download, and reshare settings before sharing.
- Make revocation block future access, while explaining that downloaded or screen recorded copies cannot be recalled.
- Strip EXIF by default. Keep venue sharing separate from analysis.
- Cascade deletion through originals, transcodes, thumbnails, clips, pose and ball tracks, vector rows, summaries, notifications, exports, share tokens, caches, and processor copies. Track completion in `deletion_jobs`; do not claim deletion while derived records still resolve.
- If a participant withdraws, delete the session or create a verifiably redacted reanalysis. Deleting only a player row while retaining the original clip is not enough.

## Red team

| Failure | Guardrail |
|---|---|
| Person detector swaps the two players and attributes errors to the wrong account | Require person to account confirmation before joining person level events to player history. |
| Vector search returns a private moment from another account | Apply access and consent filters before vector search, then authorize every candidate again after joining it to SQL records. |
| A newer engine silently changes old trends | Keep immutable runs and show the analysis version used for each chart. |
| One bad session creates a permanent trait such as “weak backhand” | Require repeated evidence, denominator, counter evidence, confidence, and validity dates. |
| Handheld camera movement looks like player movement | Store camera motion and calibration quality; downgrade or suppress affected metrics. |
| Deletion removes SQL rows but leaves a clip, embedding, cache, or summary | Run deletion through a tracked job and verify that retrieval can no longer find the deleted evidence. |
| Opponent consent is inferred from the uploader’s permission | Keep consent per participant and per purpose; private by default. |
| A free text label or imported clip instructs the LLM to reveal data | Treat retrieved text as untrusted data, not instructions; expose only bounded read tools. |
| Upload retry duplicates sessions or consent state | Use an idempotency key for upload and analysis requests. |
| Storage or model quota is reached during the demo | Preserve analysis progress, show the limit clearly, and keep the core result available without an LLM call. |

## Recommendation

Build the history foundation around **Supabase Postgres in Mumbai**, immutable `analysis_runs`, SQL first retrieval, and `pgvector` only for candidate moment ranking. Store media outside the database, keep videos private by default, and surface finite storage limits. Make summaries and player traits versioned projections with links back to evidence. Use an LLM only as a bounded reader of authenticated evidence. Ship offline browsing and cached search before adding an on device model.

## Three biggest risks

1. **False progress trends** from camera movement, poor calibration, or engine version changes.
2. **Privacy leaks** through opponent identity, sharing, vectors, or incomplete deletion.
3. **Free tier limits** interrupting uploads or removing the ability to retain every original video over time.

## Open questions for the team

- Is indefinite retention of every original video a hard requirement, even when free storage fills?
- Which first sports, rulesets, and metrics should the comparison view support?
- Will the demo show anyone outside the four team members, and what sharing scopes should be enabled?
- Should offline mode cover history browsing only, or also coach answers over cached evidence?
- Is Mumbai data placement a product requirement, or is it a latency preference?
- Which provider accounts and free quotas will actually be ready for the hackathon?