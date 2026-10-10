# Debate D4 verdict

**MODIFY.** Keep post-game film review, session memory, and a two-part simulation as the product direction. The draft is not ready to promise personalized recommendations: its sample gate is unvalidated, its cross-sport situation matcher needs a stricter schema, and the college clips cannot substantiate match-performance claims.

For the 24-hour demo, build **evidence-linked replay and history**. Show a similar moment from a clearly labeled reference source, raw observations from the user’s clip when they are reliable, and an explicit “not enough evidence” state otherwise. Keep personalized “better action” advice off. Your supplied probe results support treating camera stability as a major constraint: WASB-SBDT ran at about 50 fps on M4 MPS and found 11/12 sampled detections on static broadcast tennis, while it performed poorly on handheld badminton footage. That is team probe evidence, not an externally validated guarantee.

## Load-bearing claim check

Live sources checked October 10, 2026. **UNVERIFIED** means I did not find sufficient evidence to support the claim.

| Claim or choice | Status | Evidence and implication |
|---|---|---|
| Supabase Free can hold the demo’s relational match history | **CONFIRMED, with a storage caveat.** Free includes 2 projects, 500 MB database per project, 1 GB storage, and 5 GB egress. Mumbai (`ap-south-1`) is available. Free projects can pause after 7 days of low activity. These limits are suitable for a compact demo store, not a promise of unlimited video history. Actual clip capacity depends on encoding and retention, so that is **UNVERIFIED** until the upload profile is set. ([limits](https://supabase.com/docs/guides/platform/billing-on-supabase), [regions](https://supabase.com/docs/guides/platform/regions), [pausing](https://supabase.com/docs/guides/platform/free-project-pausing)) |
| Supabase `pgvector` is the right situation matcher | **Partly confirmed, recommendation rejected for V1.** Supabase supports `pgvector`, but its docs require a database function for similarity search because PostgREST does not expose the vector operators directly. Structured SQL filters plus a small deterministic ranker fit these court and event fields better for the hackathon. Keep vector search for later text search, if needed. ([Supabase vector columns](https://supabase.com/docs/guides/ai/vector-columns)) |
| A universal shot-family recognizer can infer clear, drop, smash, drive, lift, and net shots from pose and trajectory | **UNVERIFIED.** No target-clip result or cross-sport evaluation in the supplied probes establishes this. The draft may define candidate heuristics, but they must not be presented as validated recognition or used to issue advice. |
| The `≥20`/`≥5 per action`/rally-count gate or `P(lift >5 points) ≥ .90` establishes recommendation quality | **UNVERIFIED.** These are product thresholds, not cited sports-science cutoffs. Define lift as an absolute difference in outcome rates if retained. Repeated decisions within rallies and matches are dependent, so a simple independent-shot interval can overstate certainty. NIST describes Wilson intervals for proportions, but that does not validate a particular evidence threshold or handle match clustering for you. ([NIST proportion interval](https://itl.nist.gov/div898/handbook/prc/section2/prc241.htm)) |
| “This gets personal after 3 matches” | **WRONG as written.** Match count alone does not meet the draft’s own event-count, quality, and comparison conditions. Remove it. |
| The court dimensions support a common grid schema | **CONFIRMED for dimensions; the proposed grids remain design choices.** Official sources give badminton court dimensions of 13.40 m by 6.10 m for doubles and 5.18 m singles width; table tennis playing surface is 2.74 m by 1.525 m; tennis is 23.77 m long, with 8.23 m singles and 10.97 m doubles width. These facts support sport-specific coordinate frames, not the accuracy of a tracker or recommendation. ([Sports Authority of India badminton diagram](https://sportsauthorityofindia.gov.in/sai/public/assets/pdfs/infrastructure/Part2.pdf), [ITTF statutes](https://db.ittf.com/sites/default/files/public/2025-02/ITTF_Statutes_2025_tracked_changes_version.pdf), [ITF glossary](https://www.itftennis.com/en/about-us/organisation/tennis-glossary/)) |
| ShuttleSet is suitable for a demo reference | **Partly confirmed; original video reuse is UNVERIFIED.** The dataset page states CC BY 4.0 and describes 884 videos, including a YouTube video collection. Its paper reports 44 matches, 3,685 rallies, 36,492 strokes, and 27 players. The exact rights to show each included video are not established by those aggregate facts. Use an annotation-based top-down reconstruction only after checking the packaged annotation license and attribution; do not project source footage unless its rights are clear. ([Mendeley dataset](https://data.mendeley.com/datasets/3sp4xntp34/1), [ShuttleSet paper](https://arxiv.org/abs/2306.04948), [CC BY 4.0 terms](https://creativecommons.org/licenses/by/4.0/), [YouTube license guidance](https://support.google.com/youtube/answer/2797468?hl=en)) |
| Temporal occlusion supports the Read / Decide split | **CONFIRMED as a training format, not as proof of app effectiveness.** A 2024 meta-analysis covered 12 studies and 248 participants and found an effect for temporal-occlusion training; it also reports limitations, including inconclusive publication-bias assessment. This supports trying a two-stage interaction, not claiming it will improve this app’s users. ([Sports Medicine meta-analysis](https://pmc.ncbi.nlm.nih.gov/articles/PMC11467115/)) |
| Cloudflare Workers AI is an optional free text model | **CONFIRMED with two qualifications.** The current hosted model ID is `@cf/openai/gpt-oss-20b`, with a 128K context window and function-calling support listed on the model page. The current pricing page, updated October 9, gives 10,000 free Neurons/day and rates of 18,182 input and 27,273 output Neurons per million tokens. That is a theoretical ceiling of about 550,000 input-only or 366,700 output-only tokens per day; mixed use shares the same neuron budget. Requests fail after the daily free limit. Function calling is labeled **Beta** in the feature docs, so do not make it a core dependency. Cloudflare says it does not use customer content to train models or improve services absent explicit consent. ([model](https://developers.cloudflare.com/workers-ai/models/gpt-oss-20b/), [pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), [function calling](https://developers.cloudflare.com/workers-ai/features/function-calling/), [data usage](https://developers.cloudflare.com/workers-ai/platform/data-usage/)) |
| The hosted model has an app model file to bundle | **Not applicable to this design.** Workers AI hosts the model, so the mobile app ships no model weights. `gpt-oss-20b` is an open-weight model; OpenAI publishes its license as Apache 2.0. ([OpenAI open-weight models](https://help.openai.com/en/articles/11870455-openai-open-weight-models)) |
| Groq, Gemini, and OpenRouter are interchangeable free fallbacks | **WRONG as a privacy and quota assumption.** Groq documents `openai/gpt-oss-20b` at 30 RPM, 1,000 RPD, 8,000 TPM, and 200,000 TPD, subject to account limits. Groq says inference is not retained by default, but temporary logs can be kept for up to 30 days; it documents GCP US storage for retained data. ([Groq limits](https://console.groq.com/docs/rate-limits), [Groq data](https://console.groq.com/docs/your-data)) |
| Gemini is free for all users at a fixed public rate | **UNVERIFIED.** The current model catalog lists `gemini-3.8-flash`; free-tier availability and rate limits vary by model, project, and account, with exact quotas shown in AI Studio. Unpaid API prompts and responses may be used to improve Google products and may be reviewed by humans, so exclude player history and personal data. ([models](https://ai.google.dev/gemini-api/docs/models), [billing](https://ai.google.dev/gemini-api/docs/billing), [pricing](https://ai.google.dev/gemini-api/docs/pricing), [terms](https://ai.google.dev/gemini-api/terms)) |
| OpenRouter Free provides a stable private model endpoint | **WRONG for this use.** Current Free offers 50 requests/day and routes across 25+ free models and 4 providers. The free routing option can choose a model dynamically, and provider data practices vary. Do not send player history through it. ([pricing](https://openrouter.ai/pricing/), [free models](https://openrouter.ai/collections/free-models), [privacy](https://openrouter.ai/privacy)) |

## Exact situation-matching spec

The current direction is **not precise enough to code without adding these rules**. The numeric values below are proposed deterministic ranking defaults. They are **estimates**, not measured cutoffs or match probabilities.

1. **Use separate coordinate planes.** Store canonical coordinates in meters and include `sport`, `ruleset_version`, `format`, `reference_plane`, and `coordinate_frame_version`. For badminton and tennis, use the active court dimensions and rules lines for the selected singles or doubles format. Divide each playable half into a proposed 3-by-3 grid: left, centre, right; front, middle, back. Treat service boxes and the short/rear service lines as rule overlays, not extra grid cells. For table tennis, use a proposed 3-by-2 grid over the table for ball target/landing. Store player feet positions in a separate floor-plane coordinate frame. **Never transform a player position using the tabletop plane.**
2. **Represent doubles explicitly.** Store `focal`, `partner`, `opponent_left`, and `opponent_right`, with team, side, role, position, and tracking confidence. Require the relevant roles to be observed before comparing geometry. Never compare a doubles event as though it had one opponent.
3. **Mirror by rotating the full court.** Canonicalize the focal team to the bottom half. When the focal team starts on the far side, rotate coordinates by 180 degrees: `x → W − x`, `y → L − y`. Preserve handedness. Do not horizontally flip the image.
4. **Apply compatibility filters before ranking.** Require exact matches on sport, ruleset version, singles/doubles format, rally/service phase, and camera-quality bucket. Exclude the query rally. For any comparison that could support a personal pattern, exclude the query match as well. If the footage lacks reliable event/position evidence, return no candidate.
5. **Rank remaining candidates with an explainable distance.** Normalize each component to `[0,1]` and use this initial weighted distance: focal position `0.35`; opponent/partner geometry `0.30`; incoming source/landing zone pair `0.20`; confirmed shot family `0.10`; score-pressure bucket `0.05`. Use Euclidean distance normalized by the relevant half-court diagonal for positions. For grid zones, use Manhattan distance divided by the maximum possible grid distance. Shot family is `0` for the same confirmed label and `1` for a different confirmed label. Treat unknown features as missing, omit their weight, and renormalize. Require at least `70%` of the original weight to be present. Return up to five results only when distance is `≤0.30`, with the matched features shown. The weights, coverage, and cutoff are **engineering starting values only**. Never display them as confidence or accuracy.
6. **Keep shot-family labels unknown by default.** For this build, use `unknown` unless the user confirms the label. A future bounded badminton heuristic may emit a low-confidence *candidate* only when two independent cues agree, such as pose plus observed flight/landing zones. Proposed cues include overhead contact with a backcourt landing for clear, overhead contact with frontcourt landing for drop, steep descent after overhead contact for smash, flatter flight for drive, low contact with an upward backcourt path for lift, and low frontcourt contact/landing for net. Numeric trajectory and pose cutoffs are **UNVERIFIED** for your target camera and clips. No candidate label should drive a recommendation.

## Evidence gate and demo

For the hackathon, set **personalized recommendations to disabled**. One or two matches per user will not satisfy the draft gate, and the draft’s thresholds are not evidence of reliable advice.

Show three clearly separated kinds of evidence:

- **Your clip:** replay, timeline, and only those tracks or observations that the quality checks support. Label the informal college footage `EXPERIMENTAL CLIP`, not a badminton, tennis, or table-tennis match if it does not show that sport.
- **Team reference:** use team-recorded footage with participant consent. Label it `TEAM REFERENCE`. A juxtaposed clip can illustrate a similar situation, but call it “similar moment,” not “better action.”
- **Public annotation example:** optionally reconstruct a ShuttleSet rally from licensed annotation rows as a top-down animation. Label it `PUBLIC ANNOTATION RECONSTRUCTION`, include source and attribution, and do not display the underlying broadcast video without per-video rights confirmation.

For a later product, keep the draft’s `≥20` clean close decisions, `≥5` per compared action, and `≥3` matches only as a **minimum display eligibility floor** for a “possible pattern to test.” Count at most one comparable decision per rally and require the compared evidence to span multiple matches. These thresholds remain unvalidated. Remove the `P(lift >5 points) ≥ .90` claim until the outcome model and match-level uncertainty method are specified and evaluated on held-out matches. “Better action” requires coach review and prospective evidence. In the demo, show `x/y observations in this match` as a descriptive count with its source, not as advice.

## Session memory, database, and AI surface

**Use Supabase Postgres in Mumbai for canonical structured history. Do not add pgvector in the 24-hour build.** Keep sessions, events, evidence references, analysis runs, corrections, and retention metadata as structured records. Store compact derived metrics and only the short media needed for the demo. Keep original-video retention separate from the append-only history of analysis and correction records; a user deletion request must still be possible.

A reasonable record shape is:

- `sessions`: sport/ruleset/format, media reference, source, participant consent state, created time, retention deadline.
- `analysis_runs`: pipeline/model identifiers, run status, quality grade, versioned outputs.
- `moments`: timestamps, coordinate frame, observed participants, zones, confidence, evidence source.
- `corrections`: user-confirmed or edited labels, actor, timestamp, original value, corrected value.
- `reference_examples`: source and license metadata, plus explicit `team_reference` or `public_annotation_reconstruction` type.

**Strongest alternative:** Cloudflare D1 plus Vectorize keeps a future Worker-based API in one platform. Its current Free quotas are 5 million rows read/day, 100,000 rows written/day, 5 GB total D1 storage, and Vectorize free quotas of 30 million queried dimensions/month and 5 million stored dimensions. It is a reasonable choice if Worker-native deployment is the overriding goal, but it adds separate vector-store synchronization and does not improve this structured ranker. ([D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [Vectorize pricing](https://developers.cloudflare.com/vectorize/platform/pricing/))

Neon Free currently offers 1 GB per project and 100 CU-hours/month, with 5 GB object storage. Its public regional-latency dashboard lists Singapore but not Mumbai; exact account region availability is **UNVERIFIED**. ([Neon Free plan](https://neon.com/blog/neon-free-plan-1-gb-per-project), [regional dashboard](https://neon.com/demos/regional-latency))

Use these read-only assistant functions, with SQL doing all counts and calculations:

- `list_sessions(filters)`
- `search_moments(filters)`
- `get_metric_trend(metric, range)`
- `compare_sessions(a, b, metric)`
- `get_moment(id)`

The model may write one short evidence-grounded sentence from the returned facts and IDs. Validate every returned ID against the evidence pack. Do not expose arbitrary SQL, write tools, or direct media access to the model. Send only pseudonymous structured facts, never video, audio, names, email addresses, or provider IDs. If the model is unavailable, use deterministic templates.

For the optional text layer, use Cloudflare Workers AI `@cf/openai/gpt-oss-20b` as a server-side, best-effort summarizer. The current hosted API has no app-side model file. Keep tool execution out of the model path in this build because function calling is documented as Beta. Do not put the analysis pipeline or result rendering behind the LLM. Gemini unpaid API and OpenRouter Free should not receive player history under their current terms and provider routing. Groq has useful published limits, but its retention and US storage terms make it a weaker default for this use.

## Game simulation

**KEEP the two-stage Read / Decide interaction as a bonus after fundamentals.** It separates reading the incoming shot from choosing a response. It should only run on a clip with reliable contact and trajectory timing. A configurable freeze around 200 ms before contact is a proposed starting point, not a verified threshold.

- **Read:** freeze before opponent contact. User taps an expected landing zone. Reveal the observed flight. Show `exact`, `adjacent`, or `wrong` only if the landing zone is reliable; otherwise show `unscored`.
- **Decide:** after revealing the incoming flight, freeze before the focal player’s contact. User taps a target zone. Show legality as `inside` or `outside` based on the configured court lines. Show opponent spacing at that frame as `covered` if the nearest defender is in the same grid cell, `adjacent` if one cell away, or `separated` if two or more cells away. For doubles, use the nearest of both defenders.
- Present spacing as a frame observation, not a reachability or win probability. Show historical outcome counts only when the evidence gate is met. Do not combine these values into a “decision score,” and do not mark a hypothetical choice correct because the real point was won.

The cited temporal-occlusion research supports exploring this interaction format. It does not establish that this app or scoring system improves decisions.

## Premium UI and cross-document conflicts

Premium should come from precision, evidence, and restraint: one clear takeaway, a well-sized main clip, a timestamp and source for every claim, a correction path, and deeper visual analysis behind a tap. In portrait, use one primary clip with a reference toggle; offer synchronized side-by-side playback in a wider layout. Only claim synchronization when the contact timestamps are trustworthy. Avoid a stack of heat maps, unsupported ball-speed numbers, or decorative tracking layers on the informal clip.

Resolve these inconsistencies across the drafts:

- D05’s **“Won 4 of 5 from this position”** looks like a recommendation, while D06 sets a higher evidence gate. Replace it with a raw observed count and its denominator, labeled “in this match.”
- D06’s **“This gets personal after 3 matches”** conflicts with its own event-quality gate. Delete it.
- D06 mixes similar-clip retrieval, observed association, geometric open space, and personal advice. Give each a separate label and evidence source.
- D04’s single court plane does not fit table-tennis ball positions and player floor positions. Separate the tabletop and floor coordinate frames.
- D07’s referee language must not be used for Read / Decide geometry. A target inside the configured boundary is a geometry check, not an officiating call.
- D08’s API and MCP should use the same stable session and moment IDs as the player app. Do not create a second memory store for the assistant.
- D01’s held-out acceptance should apply to thresholds and any future recommendations. Do not tune and evaluate a cutoff on the same demo clips.

The strongest alternative is a polished “film-room” demo with no generated coaching at all: one clip, one clearly supported observation, and one labeled reference moment. That is easier to defend to a judge and more useful to a coach than a confident-sounding but unsupported instruction.

## Decision table

| Decision | Verdict | Replacement |
|---|---|---|
| Video-first coaching and session history | **KEEP** | Make replay, evidence provenance, and cross-session retrieval the core. |
| Personalized advice on the hackathon user | **REPLACE** | Disable it. Show descriptive counts and similar moments only. |
| Situation matching | **MODIFY** | Use the explicit filters, coordinate frames, roles, ranker, and honest labels above. |
| Auto shot-family recognition | **REPLACE** | Default to `unknown`; allow user confirmation. Candidate heuristics are future work. |
| Supabase plus `pgvector` | **MODIFY** | Use Supabase Postgres in Mumbai; structured SQL and deterministic ranking; no vector extension in V1. |
| Free LLM choice | **MODIFY** | Optional server-side Cloudflare `@cf/openai/gpt-oss-20b`; deterministic fallback; no tool-calling dependency. |
| Read / Decide simulation | **KEEP, gated** | Use separate Read and Decide stages with the simple observed-zone rules above. Bonus only. |
| Side-by-side “better action” clips | **MODIFY** | Label as `similar moment` or `reference example`; show source and rights provenance. |
| Premium screen treatment | **MODIFY** | One evidence-linked visual first, deeper analysis on demand, abstain visibly. |

## Required edits

1. **Replace “This gets personal after 3 matches” with:**

   > Personal patterns appear only after the relevant evidence gate is met. Match count alone is not enough. Until then, the app shows descriptive results from this match and clearly labeled similar moments.

2. **Replace the recommendation gate with:**

   > For this demo, personalized recommendations are disabled. The app may show a similar moment, a user-confirmed label, or a descriptive count from the current match. A future “possible pattern to test” may be considered only after at least 20 clean comparable decisions, at least 5 per compared action, evidence from at least 3 different matches, and no more than one counted decision per rally. These are provisional display guardrails, not validated sports-science thresholds. Do not label an action “better” until coach review and prospective evaluation support that claim.

3. **Add this coordinate and doubles schema:**

   > Every event stores `sport`, `ruleset_version`, `format`, `reference_plane`, `coordinate_frame_version`, and canonical coordinates in meters. Badminton and tennis use court planes. Table-tennis ball targets use the tabletop plane; player positions use a separate floor plane. Doubles events identify `focal`, `partner`, `opponent_left`, and `opponent_right`. For display and matching, rotate the full court 180 degrees when needed so the focal team is on the bottom half. Preserve handedness. Never compare events across different planes or formats.

4. **Replace the matching section with the filters, proposed 3-by-3 badminton/tennis zones, 3-by-2 table-tennis zones, weighted ranker, and `≤0.30` cutoff specified above. Add:**

   > The weights, feature-coverage cutoff, and distance threshold are initial engineering defaults only. They are not trained, calibrated, or interpretable as probability or accuracy. Exclude a candidate when sport, ruleset, format, phase, or camera-quality grade differs.

5. **Replace the automatic shot-family claim with:**

   > Shot family is `unknown` unless the player confirms it. A future badminton heuristic may emit a low-confidence candidate when two independent pose and trajectory cues agree. Numeric cue thresholds remain unverified for the target cameras. Candidate labels do not support personalized recommendations.

6. **Replace the demo example and “better action” copy with:**

   > The demo shows the user’s clip, a clearly labeled team reference, and optionally a public annotation reconstruction. A reference is labeled “similar moment,” never “better action.” Public reconstructions include source and attribution. Do not show third-party source video unless reuse rights for that video are confirmed. If tracking quality is insufficient, show “No reliable tracking in this clip.”

7. **Replace the game-simulation scoring text with the Read / Decide rules above. Add:**

   > Read scores only against a reliable observed landing zone. Decide shows target legality and defender spacing at the freeze frame. It does not calculate a win probability or a composite decision score. Low-quality events remain unscored.

8. **Replace the memory and LLM section with:**

   > Supabase Postgres in Mumbai is the canonical structured store for sessions, moments, analysis runs, reference provenance, and user corrections. V1 uses structured SQL and deterministic matching without `pgvector`. Assistant functions are read-only and return verified IDs and metrics; the model may summarize those results but cannot calculate them, issue writes, or access raw media. Cloudflare Workers AI `@cf/openai/gpt-oss-20b` is optional and best-effort. Function calling is Beta, so it is not required. The deterministic UI works if the LLM is unavailable.

9. **Replace the D05 example “Won 4 of 5 from this position” with:**

   > In this match: 4 of 5 observed points ended in a win from this zone. This is a descriptive count, not a recommendation.

## Open questions only the team can answer

1. Which team-recorded clips and on-screen participants have consented to upload and judge-facing playback?
2. Do you want the demo to include a clearly labeled top-down reconstruction from public ShuttleSet annotations, subject to confirming the packaged annotation license and attribution?
3. How long should original uploaded clips remain available after the demo, given the Free storage quota?
4. Which team member will be the focal player in the demonstration, and which clip best supports the specific interaction you want to show?

## Recommendation

Build the first-match film room, persistent session records, evidence-linked moments, and a restrained reference comparison. Use team-shot reference footage and, if cleared, an annotation-only public reconstruction. Keep user-specific recommendations and Read / Decide behind reliable event evidence and outside the core demo path.

## Three biggest risks

1. The informal handheld clip may not support reliable court, contact, or ball tracking.
2. A reference clip can look like a performance claim unless its source and role are explicit.
3. Supabase Free has limited media storage and can pause after inactivity, so uploaded-video retention and demo availability need a concrete plan.

## Open questions only the team can answer

See the four team decisions above.