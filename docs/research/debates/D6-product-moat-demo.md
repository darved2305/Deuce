# Debate D6 verdict

**MODIFY the product promise, moat, acceptance tests, demo, and name. Keep the premium, visual, evidence-first direction.** The current drafts describe a useful end state, but imply substantially more perception quality and product breadth than the evidence supports. For 24 hours, build a cloud-backed match record and replay flow, make scorekeeping human-confirmed, and label automatic tracking as experimental until it passes a stated quality bar.

The team’s probes are the strongest evidence about the actual footage you can show. On nine static elevated badminton clips, WASB detected the shuttle in 51% of sampled frames at roughly 85% precision; TrackNetV3 detected it in 69% at roughly 50% precision. Both performed poorly on portrait input when letterboxed. A casual catch clip produced zero detections from the tested trackers. The team also reports that WASB worked on static broadcast tennis, with 11 of 12 sampled detections correct, but poorly on handheld badminton footage. These are small probes, not broad performance guarantees. ([vision-engine probe](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:20), [team feasibility probe](/Users/Hetansh/Github/elevate1.0/research/out/07-adversarial-feasibility.md:100))

## Decision ledger

| Decision | Verdict | Evidence and replacement |
|---|---|---|
| “Any racket-sports video” produces trails, landings, movement, rallies, referee decisions, and coaching | **REPLACE** | This capability is not supported across arbitrary phone footage by the probes. The draft’s promise at [02 §1](/Users/Hetansh/Github/elevate1.0/docs/02-product-and-moat.md:6) should be limited to the exact capabilities and footage conditions that pass validation. |
| One sport pack makes a new sport work without training | **MODIFY** | Rules and court configuration can be versioned data. They do not make a vision model recognize an unsupported sport or handheld view. RacketVision’s 2026 benchmark covers badminton, table tennis, and tennis, and its multi-sport model was trained on those sports. This supports using public pretrained models, under the team’s interpretation, but does not establish transfer to arbitrary phone footage or to squash, padel, and pickleball. [RacketVision paper and code](https://ojs.aaai.org/index.php/AAAI/article/download/37362/41324), [repo](https://github.com/OrcustD/RacketVision), [feasibility review](/Users/Hetansh/Github/elevate1.0/research/out/07-adversarial-feasibility.md:27) |
| Cloud engine that feels seamless | **MODIFY** | Keep cloud inference, as decided. Use direct-to-object-storage upload, resumable parts, and visible server job states. Upload time, cold starts, end-to-end turnaround, and India latency are **UNVERIFIED** until measured. Cloudflare’s free Workers request body limit is 100 MB, so don’t send full match videos through a Worker; R2 documents a 10 GB-month free storage tier, operation allowances, and no internet egress charge. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [R2 pricing](https://developers.cloudflare.com/r2/pricing/), [deployment research](/Users/Hetansh/Github/elevate1.0/research/out/03-api-platform-deploy.md:64) |
| “Something useful within seconds” before upload finishes | **REPLACE** | The current instant rally barcode is inferred locally from audio, then presented as rally candidates. The prototype also says “114 rallies found in 6 s, from sound.” That speed and count have no verified evidence in the supplied probes. [05 §3.2](/Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md:43), [Analysing prototype](/Users/Hetansh/Github/elevate1.0/ui/Analysing.dc.html:33) |
| Evidence-first premium UI | **KEEP, with fixture labels** | The dark, video-first visual direction, concise captions, and ability to open source frames are the strongest product design choices. The prototype values such as `62 km/h`, `95% landing area`, `9/9 tracked`, and `114 rallies` need a visible `ILLUSTRATIVE FIXTURE` label unless they come from an analysis run with replayable source frames. [02 §§3–4](/Users/Hetansh/Github/elevate1.0/docs/02-product-and-moat.md:38), [Rally prototype](/Users/Hetansh/Github/elevate1.0/ui/Rally.dc.html:61), [Referee prototype](/Users/Hetansh/Github/elevate1.0/ui/Referee.dc.html:64), [Main prototype](/Users/Hetansh/Github/elevate1.0/ui/Main.dc.html:94) |
| AI referee | **REPLACE for this build** | BWF’s review system uses IN, OUT, and NO DECISION. That confirms a useful review interaction pattern, not the accuracy of a phone-derived call. [BWF Technical Officials instructions](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/Section%204.1.1%20-%20Instructions%20to%20Technical%20Officials%20%28ITTO%29.pdf). For the demo, say **“review this moment”** and let a person confirm or correct the score. Remove synthetic uncertainty ellipses and IN/OUT stamps unless the run is calibrated and passes a held-out line-call test. |
| Recommendations shown as “better action” clips | **MODIFY** | Keep the side-by-side replay concept, but do not claim “better” from the current sample sizes. The coaching draft itself requires at least 20 close decisions, 5 per compared action, and 3 rallies before making that recommendation; below the gate it says to show “a similar moment.” The prototype’s 4/5 comparison does not meet that gate. [06 §3.2](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:56), [Compare prototype](/Users/Hetansh/Github/elevate1.0/ui/Compare.dc.html) |
| Game simulation | **KEEP as a later extra** | The proposed freeze at opponent contact asks players to choose before they have seen the incoming flight. The two-beat “predict the landing, then choose a return” design fixes that problem, but it remains after the fundamentals, as the draft says. [06 §6](/Users/Hetansh/Github/elevate1.0/docs/06-coaching-intelligence.md:119) |
| API, SDK, embed, MCP as a moat | **REPLACE the moat claim** | PB Vision already documents a partner API, Node SDK, and MCP endpoint; its API requires partner approval and paid plan/add-on. Smashpoint also documents MCP support. PlaySight and Stupa advertise multi-sport racket capabilities. These confirm that API/MCP and multi-sport coverage are not distinct advantages by themselves. [PB Vision API](https://help.pb.vision/en/articles/2793895-api-partner-guide), [SDK](https://github.com/pbv-public/partner-sdk-nodejs), [MCP](https://roadmap.pb.vision/en/help/articles/8878232-ai-coach-and-external-ai-chat), [Smashpoint MCP](https://smashpoint.pro/help/ai-and-mcp/what-you-can-ask-an-ai-agent), [PlaySight sports](https://playsight.com/our-sports/), [Stupa](https://stupasports.ai/broadcasters) |
| Name | **REPLACE** | All five candidates have material collisions or registered `.dev` domains. None has verified trademark clearance. Shortlist a fresh name, but keep the hackathon working title “Elevate Rally” until the team checks app stores, domains, and trademarks. |

## PS coverage and realistic 24-hour tiers

The checklist semantically covers most of the supplied problem statement, but it overstates what is directly in the supplied text. P1–P4 (“player profiles,” “performance history,” “rankings,” “development data”) and P12 (“local communities remain disconnected”) are **inferred product requirements**, not verbatim requirements in the core statement provided here. The checklist says every row is lifted verbatim, so that claim is **WRONG**. The list of six sports is also missing from its acceptance test. Label inferred rows and list all six sports, even if most are unsupported. [01 intro and C1](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:1), [P1–P12](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:39)

The current acceptance tests often lack a dataset, denominator, pass threshold, or reproducible setup. “Under a target time set in 03” has no value here; “10-rally clip matches hand-kept score” needs a labeled test clip and score protocol; `coverage %` needs labeled frames and an explicit definition. Mark each result `pass`, `unmet`, `unknown`, or `could-not-run`. A demo result is not a pass on its own.

| PS area | 24-hour tier | Verdict on current acceptance test |
|---|---|---|
| One normal camera; no team training or venue-specific fine-tuning | **Core constraint** | Keep phone video as the only sensor. Record source, camera, resolution, fps, model/checkpoint hash, and sport pack version. A hash documents what ran; it does **not** prove generalization. Test on a held-out venue clip. |
| Across sports and sport differences | **Designed for autonomous vision** | Name badminton, table tennis, tennis, squash, padel, and pickleball in the matrix. Support only a tested slice. “Same engine version” does not show the model works in each sport. The future badminton change from 3×21 to 3×15 is approved to take effect 4 Jan 2027, so rulesets need versions and effective dates. [Japan Badminton Association announcement](https://www.badminton.or.jp/news/detail/2156) |
| Persistent match record, history, corrections | **Core** | Keep one canonical `match_id`, immutable analysis run, user-confirmed correction, and retrieval across at least two saved matches. Search can be deterministic by sport/date/result; natural-language coaching is not required. |
| Scorekeeping | **Assisted** | User confirms or corrects point winner. Replace automatic exact scoring of a 10-rally clip as a core pass condition. For any later automatic test, publish the annotated rallies, baseline score, and exact-match threshold. |
| Ball/shuttle trajectory and speed | **Experimental** | Show observed and inferred track points separately. Measure coverage against labeled frames. Do not report “hit-to-bounce ground average” as shuttle speed: it is not the airborne speed. Any speed estimate needs calibration, enough observed timestamps, a defined method, and a measured error bound. |
| Player statistics | **Assisted or Experimental by metric** | Show only statistics derived from actual records and link each figure to its frames. Movement in court metres requires valid court calibration; counts and rally lengths need correct event segmentation. |
| Dispute review / referee | **Assisted** | Show evidence frames and a human confirmation action. Automatic IN/OUT remains Experimental and cannot update score without confirmation. A planted clear/close case is not a sufficient test without ground truth and varied camera conditions. |
| Player understanding, comparisons, coaching data | **Designed**, with a Core evidence viewer | First show a replay and factual summary. A “better action” needs the evidence gate in `06`; until then, label the second clip “similar moment,” “reference,” or “simulated.” |
| Compare with others; find opponents; meetups; tournaments; court booking | **Designed** | These are separate identity, consent, location, availability, inventory, and integration problems. Stubs and one screen do not satisfy real user outcomes. Keep them out of the 24-hour demo. |
| Coaches’ continuous data | **Designed** | Keep consented sharing and a coach-view concept on the roadmap. Do not claim a continuous coach workflow from a static screen. |
| Historical learning and predictions | **Designed** | Two searchable records can prove persistence. They cannot support personalized trend claims or opponent tendencies. Show sample size, capture comparability, and uncertainty when these features are eventually built. |
| Mobile | **Core** | Android APK installed and tested on the team phones is a realistic acceptance test. Current Expo docs list SDK 57 and React Native 0.86.0; confirm compatible dependencies before pinning. [Expo current versions](https://docs.expo.dev/versions/latest/), [Expo upgrade reference](https://docs.expo.dev/bare/upgrade/) |
| Offline | **Assisted** | Support cached match playback, saved summaries, local score correction, and a visible queued/resumable upload. New analysis requires connectivity because the chosen engine is cloud-based. **Offline inference is unverified and out of scope.** |
| Voice | **Designed** | A voice query and clip answer is a separate integration and reliability task. Do not reserve a core demo acceptance test for it. |
| API, SDK, embeddable components, MCP | **Core thin vertical slice** | All surfaces should read the same match record. Implement one read API, one TypeScript SDK call, one read-only MCP tool, and one minimal embeddable replay. Defer Python SDK, webhooks, partner auth, and production guarantees. |
| Beyond racket sports | **Experimental demonstration** | A catch/bottle clip can demonstrate upload, job status, and user-guided pixel tracking. If it only uses the same ingestion pipeline, it does not prove sport-model generalization. The supplied probe found zero tracker detections on a casual catch clip. [Vision-engine probe](/Users/Hetansh/Github/elevate1.0/docs/04-vision-engine.md:27) |

**Sport scope:** badminton is the primary sport for the replay and selected public footage; table tennis gets sport/rules metadata and manual scorekeeping; tennis gets the same record/manual-score support, with static-view ball tracking clearly labeled experimental. Do not claim autonomous tracking for squash, padel, or pickleball in this build. This is a deliberately partial answer to a six-sport problem, not a solution to all six.

**Model and footage evidence:** WASB-SBDT’s repository is MIT-licensed, but the repo links to external checkpoint downloads whose checkpoint terms must be checked separately. The team reports a 5.8 MB checkpoint and ~50 fps on M4/MPS; that is a team probe, not an Android or cloud service benchmark. TrackNetV3’s repository lists MIT code and pretrained checkpoints, but its cited benchmark numbers do not establish accuracy on handheld badminton phone footage. RacketVision is a newer multi-sport research candidate; its exact checkpoint redistribution terms and suitability for this demo remain **UNVERIFIED**. [WASB-SBDT repo](https://github.com/nttcom/WASB-SBDT), [WASB model zoo](https://github.com/nttcom/WASB-SBDT/blob/main/MODEL_ZOO.md), [TrackNetV3 repo](https://github.com/qaz812345/TrackNetV3), [RacketVision feasibility review](/Users/Hetansh/Github/elevate1.0/research/out/07-adversarial-feasibility.md:27)

## Moat verdict

The current statement describes a **route to a moat**, not a moat today. Competitors already offer analysis and broader sports coverage; a match record with evidence links is a good wedge, but its defensibility depends on repeat use, consent, correction quality, and whether those records actually improve coaching or distribution. That retention and data advantage are **UNVERIFIED**.

**Replacement pitch text:**

> We do not have a moat yet. Our wedge is a phone-first match record where each displayed insight opens its supporting frames and the system withholds uncertain calls. We believe repeat, consented match records and corrections could make player and coach comparisons more useful over time; retention, data advantage, and organizer distribution remain hypotheses.

This is more defensible to a VC-minded judge because it separates the built wedge from the future claim. The strongest alternative is to pitch a pure developer infrastructure company. Reject that as the hackathon wedge: API/MCP availability is already competitive table stakes, and the requested player experience needs a clear first use.

## Five-minute demo run sheet

**Use DeX over a tested USB-C-to-HDMI adapter, no QR code.** A phone-to-5G upload is part of the demo; the judges do not need to join a website. Rehearse the adapter, selected clip, upload, and fallback path before the event. The exact cloud turnaround is **UNVERIFIED**; never show a fake progress percentage or imply a cached result was just computed. Direct multipart upload to R2 and asynchronous job polling fit the published free-tier limits better than proxying video through Workers. [R2 pricing and upload guidance](https://developers.cloudflare.com/r2/pricing/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)

| Time | What judges see | Presenter |
|---|---|---|
| 0:00–0:25 | One sentence: “Import a match, review evidence-linked events, correct the score, and keep the record.” Show the capability strip: badminton replay target; table tennis manual record; tennis manual record with experimental static-view tracking. | 1, narrator |
| 0:25–1:10 | Record a 5–8 second informal clip at the venue: catch, bottle hit, or bounce beside a taped line. Say this is a free-play object clip, not a match or proof of court officiating. | 2, phone operator |
| 1:10–2:00 | Start upload from the phone over 5G. Mirror real bytes sent, upload/job ID, and server-reported stages. Don’t invent a percentage or predict a finish time. | 4, cloud/API |
| 2:00–2:40 | If the live tracking output passes a quality check, show the observed pixel path and evidence frames. Otherwise show the raw clip and say the tracker did not establish a stable path. Do not show a court metric or line call. | 3, analysis/evidence |
| 2:40–3:55 | Open a pre-analysed badminton clip and show its source attribution, match ID, replay, and any result tied to actual frames. State that this is a saved analysis, not the live upload. | 3, analysis/evidence |
| 3:55–4:35 | Show the same record through the read API, TypeScript SDK, embeddable player, and read-only MCP tool. | 4, cloud/API |
| 4:35–5:00 | Close with the honest boundary: what passed, what abstained, and what the team would validate next. | 1, narrator |

**Clip and rights:** A Wikimedia Commons EuroGames badminton recording is available under CC BY-SA 4.0. It is a candidate source, not automatically a good analysis clip: the inspected video is low-resolution and shows multiple courts, so select and validate the exact rally segment before the event. [Commons source page](https://commons.wikimedia.org/wiki/File:Badmington_Competition_EuroGames_2_2023_in_Wankdork,_Bern.webm). The Mendeley badminton dataset is marked CC BY 4.0 at dataset level, but that alone does not establish redistribution rights for every underlying broadcast-derived video. **Do not treat every dataset clip as publicly demo-cleared.** [Dataset page](https://data.mendeley.com/datasets/3sp4xntp34/1), [rights review](/Users/Hetansh/Github/elevate1.0/research/out/07-adversarial-feasibility.md:67)

**Exact fallbacks:**

- **DeX or HDMI fails:** hold the phone for the live capture, then use a pre-recorded screen capture of the same build and label it “saved demo.” No QR code.
- **5G is slow:** try the other team phone as hotspot. If upload remains pending, show the real queued state, then switch to the saved analysis and identify it as saved.
- **GPU cold start or job takes longer than the demo slot:** show the job ID and actual pending stage, then pivot to saved analysis. Don’t fabricate completion.
- **Live tracking fails:** show the raw clip and explain that the tracker abstained; continue to saved badminton replay.
- **No validated/licensed badminton segment:** use a consented team recording if appropriate; otherwise show only fixture data marked `ILLUSTRATIVE FIXTURE`. Don’t pass off a mock result as an analysis.
- **API/MCP integration is incomplete:** show the actual working API route and response. Don’t imply the other surfaces are implemented.

## Naming

Live public checks on **2026-10-10** found these collisions. This is an app/domain screen, **not formal trademark clearance**; trademark status for all candidates is **UNVERIFIED**.

| Candidate | Collision evidence | Verdict |
|---|---|---|
| Vantage | Exact Vantage Tennis app listings on [Google Play](https://play.google.com/store/apps/details?hl=en_US&id=com.mycompany.vantagetennis) and [Apple’s App Store](https://apps.apple.com/gb/app/vantage-tennis-court-sniper/id6762503511); `vantage.dev` is registered ([RDAP](https://rdap.org/domain/vantage.dev)). | Reject |
| Hindsight | Active Hindsight AI sales product ([site](https://www.usehindsight.com/terms)); `hindsight.dev` is registered ([RDAP](https://rdap.org/domain/hindsight.dev)). | Reject |
| Sightline | Active sports recruiting/tracking product ([site](https://sightlineapp.us/)); `sightline.dev` is registered ([RDAP](https://rdap.org/domain/sightline.dev)). | Reject |
| Second Look | Existing video review/coaching product ([site](https://www.getsecondlook.app/)); `secondlook.dev` is registered ([RDAP](https://rdap.org/domain/secondlook.dev)). | Reject |
| Ghostline | Existing app and software company ([app](https://www.ghostline.app/privacy-policy), [company](https://ghostlinetech.com/software.html)); `ghostline.dev` is registered ([RDAP](https://rdap.org/domain/ghostline.dev)). | Reject |

Three better names to screen: **CourtMemory**, **RallyMirror**, **MatchEcho**. The preliminary exact-match search did not establish trademark or app-store availability. RDAP returned no registration object for their `.dev` domains at lookup time: [courtmemory.dev](https://rdap.org/domain/courtmemory.dev), [rallymirror.dev](https://rdap.org/domain/rallymirror.dev), [matchecho.dev](https://rdap.org/domain/matchecho.dev). Treat these as a shortlist only.

## Required edits

1. In [02-product-and-moat.md §1](/Users/Hetansh/Github/elevate1.0/docs/02-product-and-moat.md:6), replace the current promise with:

   > Import a match. Review an evidence-linked replay on footage the current capability matrix supports. Confirm or correct score events. Keep one persistent record available in the player app and through a thin developer API. Analysis runs asynchronously in the cloud; unsupported or unclear measurements are withheld.

2. In [02 §5](/Users/Hetansh/Github/elevate1.0/docs/02-product-and-moat.md:56), replace the two-sentence moat claim with the “We do not have a moat yet” text above. Keep evidence-linked history and organizer distribution as hypotheses.

3. In [01 intro and C1](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:1), remove “Every line below is lifted from the problem statement.” Mark P1–P4 and P12 as inferred or product additions. Add all six named sports to the capability matrix and make unsupported sports visibly unsupported.

4. In [01 checklist](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:19), assign the tiers in this report. Add a test clip/source, camera condition, ground truth, denominator, and pass threshold for every vision acceptance test. Preserve `pass`, `unmet`, `unknown`, and `could-not-run` separately.

5. In [01 T2–T6](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:31), change automatic scoring and line calling to Assisted/Experimental. Remove airborne “hit-to-bounce ground average” speed as a valid speed definition. Define trajectory coverage against labeled frames and separate observed points from inferred ones.

6. In [01 I1/I6](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:60), replace the undefined first-result time target with measured upload and job stages. Define offline as cached playback, correction, and queued/resumable upload; state clearly that new analysis needs connectivity.

7. In [01 B2](/Users/Hetansh/Github/elevate1.0/docs/01-ps-fundamentals.md:72), rename the informal clip proof to “shared ingestion and assisted tracking demo” unless an unassisted tracker produces a validated cross-sport result.

8. In [05 §3.2–3.3](/Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md:43), remove the local audio-derived rally barcode and “114 rallies in 6 s.” Replace it with video preview, upload bytes, and actual server job state. Never render inferred rally counts as confirmed events.

9. In the eight [UI prototypes](/Users/Hetansh/Github/elevate1.0/ui/Main.dc.html), mark every unsupported static number and result as `ILLUSTRATIVE FIXTURE`. Use a run manifest and evidence frame links for any value presented as measured.

10. In [07-ai-referee.md](/Users/Hetansh/Github/elevate1.0/docs/07-ai-referee.md), make “review available” the default outcome. Keep score updates human-confirmed. Remove the 2 m taped-line in/out demo as an automated referee pass unless its call is compared against recorded ground truth under a documented camera setup.

11. In [05-app-experience.md](/Users/Hetansh/Github/elevate1.0/docs/05-app-experience.md), reduce the hackathon navigation to **Matches**, **Add**, and a compact **Today** entry. Defer widgets, digests, reminders, social challenge, coach network, and calendar.

12. In [02 §7](/Users/Hetansh/Github/elevate1.0/docs/02-product-and-moat.md:103), reject the five current names and use “Elevate Rally” as the working name until the team completes trademark and app-store checks.

## Recommendation

Build the badminton-first cloud match-record vertical slice: upload a short clip, persist the canonical match record, let the user confirm the score, show a replay with only evidence-backed overlays, and retrieve the same record from a minimal API, SDK, embed, and MCP tool. Treat table tennis and tennis as manual-record support, automatic tracking as experimental, and the informal college clip as an upload plus assisted-tracking demo. Free hosting is not guaranteed by “free tier” alone: Modal currently lists $30 monthly compute credit and GPU rates, but usage beyond credit can be billed, and its Starter workspace is limited to three seats. Confirm billing controls and eligibility before relying on it. [Modal pricing](https://modal.com/pricing), [GPU requirements](https://modal.com/docs/guide/gpu)

### Three biggest risks

1. **Vision quality:** handheld and informal clips may produce no reliable object track, and current static-camera probes are narrow.
2. **Demo delivery:** upload connectivity, GPU cold start, and cloud turnaround are unmeasured; rehearse exact fallbacks and show true pending states.
3. **Trust and scope:** fabricated-looking numbers, unlicensed footage, or calling an experimental estimate a referee ruling will undercut the evidence-first pitch.

### Open questions only the team can answer

1. Which exact badminton clip will you use, and who will verify its rights and the chosen rally’s tracking quality?
2. Which phone is the reference capture device, and what clip length/file size will you rehearse over venue 5G?
3. Who owns the cloud deployment and confirms it cannot exceed the team’s free-credit/billing limit?
4. Which four API/platform surfaces will be genuinely demo-ready: API, TS SDK, embed, MCP?
5. Will the team present the live informal clip as an assisted pipeline demo if automatic tracking fails?