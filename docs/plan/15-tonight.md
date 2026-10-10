# 15 · Tonight: a working APK first, then polish in parallel

Submission is at 8:00. Every item below **continues the branch you are already on**; nobody switches
context. Stage 1 is the bare loop that must work on the A55 with real footage. Stage 2 tracks start
the moment your Stage 1 item is merged, and run in parallel. Item IDs (A1…) refer to
`docs/plan/13-additional-plan.md` (#41) for the full method and parameters.

## Where we are (from merged PRs)
- **Engine:**
  - TrackNetV3 port is exact (#22).
  - Real candidates on the working clip (#24).
  - D-FINE boxes (#33).
  - First real `window_result.v1` (#37, `engine/eval/runs/h13g_w1/`).
  - Submit endpoint (#5).
- **App:**
  - Frame-exact overlay (#16).
  - Clip check (#31).
  - Setup screen with court-corner taps and who-is-me (#38).
  - Audio rally candidates (#39).
  - Recorded engine run drawn on the real video, plus an analysis client, sync and screens not yet in navigation (#42).
- **API:**
  - Live health and auth (#11).
  - Dispatch RPCs (#8).
  - Dispatch, cursor, internal routes (#28) and sessions/R2 upload (#29), both open.
- **Contracts 1.6.0:** #20 fixed, segments, rules (`@elevate/rules` bwf_3x21, 45 law-cited vectors).

## Stage 1 · the bare loop (must work by 8:00)
One real clip on the A55: pick → upload to R2 → analysis on Modal → events → the app draws the live
trail and the overview.

| Person | Finish on your current branch | Done when |
|---|---|---|
| **Nikhil** | #28 and #29 must-fixes (resync vendor to 1.6.0; Modal timeout leaves the run `queued`; 503 for `engine_unavailable`; `{parts:[{n,url,expires_at}]}`; reject `segments` until built; complete ≤ declared bytes), then merge and deploy. **Add `GET /v1/artifacts/{id}`** (contracts §4: ownership check, signed GET ≤ 15 min, G6 count). Without it the app cannot read window results. | A real anonymous user on the A55 gets `session_id` → upload completes → `POST /v1/analyses` returns a real `modal_call_id` → cursor shows `analysis.started` and `window.completed` → `GET /v1/artifacts/{id}` returns a signed URL that downloads the window JSON |
| **VrajVed** | Make Modal `analyze` run the window pipeline you already proved in `window_clip` (#37) on the uploaded original: read from R2 with the engine token; per ~6 s window, upload `window_result.v1` to `analysis/{analysis_id}/{engine_version}/windows/{n}.json`, `POST /internal/analyses/{id}/artifacts`, then `POST /internal/analyses/{id}/events` `window.completed` with `window_seq`, `emit_range_us`, `artifact_id`; `analysis.started` carries `engine_version`; `analysis.failed` on error. Add the **static-candidate filter** (A3: a candidate within 12 px of the same spot in more than 40% of a window's frames is dropped; tune on dev clips only) | The cursor shows real `window.completed` events for a real upload; the window JSON validates against the schema; precision on `fixture_trail` rises above 70.0% (#37) with the back-wall spot gone |
| **darved** | Wire #42's `HttpAnalysisClient`, `AnalysisSync`, `AnalysingScreen` and `MatchOverviewScreen` into navigation. Upload through `/v1/sessions` (parts as objects, at most 3 retries per part, G11). On each `window.completed`: fetch the artifact by `artifact_id`, add its rows to `WindowResultStore`, and redraw. Label live data "live engine", never "recorded". | On the A55, a picked clip uploads, the analysing screen shows real stages, and the trail appears on the real video window by window from the live backend |
| **Hetansh (Person 4)** | Turn #37's window into `fixtures/fixture_window_0003.json`, renamed by its real `window_seq` (validated, provenance kept). Save the first live run's cursor as `fixtures/fixture_events.jsonl`. Watch CI and merge on green. Cloudflare daily check. | The fixtures validate in CI; `main` stays green |

## Stage 2 · polish tracks (start as soon as your Stage 1 item is merged)
Each track is additive: new fields are optional, new screens sit behind the overview, and nothing
in Stage 1 changes.

### Track S · see the whole court, even when the camera cannot (SwingVision-style)
The four corners the user taps on the setup screen (#38) give the court's homography `H`. With `H`,
the parts of the court that are out of frame are **geometry, not guesses**: the court template is
known (13.40 × 5.18 m singles / 6.10 m doubles).
- **VrajVed:**
  - Compute `H` from `session_spec.calibration_input.points_px` (OpenCV `findHomography`, then the reprojection residual and tier A/B/C as in `04` §2), and send it in `finalize` as `match.v1.calibration`.
  - Fill the player rows' `foot_x_m` / `foot_y_m` (D-FINE foot point through `H`; withheld when off-court or tier C).
  - Landing `contact_xy_m` only for observed floor contact.
  - Fixed cameras use `H` as is; moving cameras need A1 (camera path) first.
- **darved:**
  - **Court completion overlay:** draw every court line through `H` on the real video, extending past the frame edge, solid where visible and dashed outside, labelled "court from your corner taps".
  - **Top-down mini-court:** the full court with players' feet over time, shuttle landings, and zones `F1…B3`.
  - Off-frame shuttle flight is dashed and marked "estimated" only when the engine sends a fitted path (B1); never invented.
- **Nikhil:** persist `calibration` and the player metres from `finalize`; return them in `match.v1`.
- **Proof:** on `h13g_singles` (near baseline off-frame), the extended near baseline lines up with the visible service lines, and court coordinates of the hand-labelled frames land inside the court.

### Track T · tracking of each thing
- **VrajVed:**
  - Kalman tracklets for the shuttle (`04` §2 stage 4).
  - Player association with track ids `t_*`, so `players` rows are contract-valid; #37 had to leave them empty.
  - Hit candidates from audio onsets plus direction change (A5).
- **Nikhil:** rallies and moments from hits; shot records (§7).
- **darved:** rally list, hit markers on the timeline, shot chips (data catalog `14`).

### Track M · moving camera and the split-screen demo
- **VrajVed:** always-on camera path (A1, prototype proven at 7.04 → 0.27 px on a real 1080p60 handheld original).
- **Hetansh:** renders the shaky-vs-steadied split-screen demo clip from a friend's real clip for the pitch.

### Track C · chunked real-time (judges' ask)
Contract merged (#36). The order:
1. Nikhil adds the segment routes.
2. VrajVed polls `/internal/.../segments`.
3. darved adds the segmenter.

Start only after Stage 1 works. Until then the demo says "results stream as each window finishes", which is already true.

## Rules that still hold
- Nothing synthetic: overlays sit on real frames, and estimated parts are dashed and labelled.
- Every number shows its denominator.
- `main` stays green: if you break it, revert first.
- PRs carry real proof.
