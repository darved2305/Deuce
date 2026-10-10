# Track 12: Clip-based recommendations, opponent analysis and game simulation

**Verdict:** Build a court-normalized, training-free event search that finds genuinely similar moments, then keep observed results, geometric estimates, and simulated alternatives visibly separate. With one match, show a similar clip and a top-down option, not a personalized “best action.” The strongest hackathon demo is a synchronized real replay beside a labeled alternative, with a decision game that reveals what happened after the player chooses.

## Verified evidence

| Research | What it supports | What it does not establish |
|---|---|---|
| [TacticFlow](https://wujiang5521.github.io/static/publications/tacticflow/tacticflow.pdf) represents a rally as a sequence of multivariate stroke events. It notes that tactics change in response to opponents, that players vary their choices in similar situations, and that the same tactic can have different results in different situations. It also demonstrates visual comparison and drill-down. | Match several features of a situation and its short sequence, then show the evidence visually. | A universal “best shot” rule. |
| [ShuttleSet](https://arxiv.org/abs/2306.04948) and its [official data README](https://github.com/wywyWang/CoachAI-Projects/blob/main/ShuttleSet/README.md) provide a useful badminton schema: stroke type, time, score, player and opponent locations, landing location, and outcomes. ShuttleNet uses rally progress, player style, shot type, and positions for stroke forecasting. ([ShuttleNet paper](https://ojs.aaai.org/index.php/AAAI/article/view/20341)) | Badminton is the richest starting point for an explicit public tactical schema. | That elite badminton data predicts a college player’s results, or that dataset annotations grant rights to source videos. |
| Sports-play retrieval work such as [Interactive Sports Analytics](https://authors.library.caltech.edu/records/1b1rt-4ym21/) and [Chalkboarding](https://la.disneyresearch.com/publication/chalkboarding/) explores retrieval by trajectory and visual patterns. | Nearest-neighbor retrieval and a visual “show me a similar play” interaction have research precedent. | That a learned ghosting model is needed. One example, [Data-Driven Ghosting](https://la.disneyresearch.com/wp-content/uploads/Data-Driven-Ghosting-using-Deep-Imitation-Learning-Paper1.pdf), trains on soccer data, so its learned-model approach is outside this project’s no-training constraint. |
| Tennis [Expected Shot Value](https://doi.org/10.1515/jqas-2018-0036) and [VON CRAMM](https://arxiv.org/abs/2005.12853) condition shot value on player and ball positions. | Separate shot selection from execution risk, and account for both players’ positions. | A ready-to-use amateur model from one phone camera. The cited work relies on richer tracking data. |
| A 2024 [temporal-occlusion meta-analysis](https://link.springer.com/article/10.1007/s40279-024-02073-6) covered 12 intervention studies and 248 participants, and found evidence of improved anticipation and transfer. | A freeze-and-predict interaction is a defensible training format. | That this app will improve match performance, or that its scoring will be correct. |
| [NIST’s confidence-interval guidance](https://itl.nist.gov/div898/handbook/prc/section2/prc241.htm) describes Wilson intervals for proportions and notes issues with simple symmetric intervals. | Show uncertainty with counts; avoid treating a small percentage as a stable fact. | A universal sports-specific minimum sample size. |

## Situation representation

Treat the situation as the **decision state immediately before the focal player’s action**, not as a visually similar frame. Coordinates should come from the user’s court-corner setup, normalized to the court and mirrored so the focal player is always on the same side. Keep handedness as a separate field.

Store one record per contact:

```text
sport, ruleset, singles_or_doubles, court_config
match_id, rally_id, stroke_index
contact_frame, contact_time, clip_start, clip_end
score_before, serve_or_rally_phase, pressure_bin
focal_player_id, opponent_id, handedness
focal_xy, opponent_xy, their velocities and confidence
incoming_shot_family, source_zone, landing_zone, height_class
focal_forehand_or_backhand
chosen_shot_family, target_zone, landing_zone
previous 2–3 shot types and zones, rally_length
point_winner, rally_end_reason
per_field_confidence, clip_rights_status, source_provenance
```

Use a per-sport adapter for court dimensions, scoring, legal actions, and pressure bins. Do not compare raw score or court distances across sports. TacticFlow’s analysis specifically notes that attributes differ by sport, such as the importance of ball height in badminton and the different court scales. ([TacticFlow](https://wujiang5521.github.io/static/publications/tacticflow/tacticflow.pdf))

Pose around contact is optional data for technique comparison. It should not determine whether two tactical states match.

## Training-free situation matching

Use a deterministic nearest-neighbor search. Initial weights and cutoffs below are **design estimates**, not results from the cited papers.

1. **Hard-filter** on sport, ruleset, format, event phase, and adequate contact/location confidence. Exclude the query event and its rally. For retrospective evaluation, also exclude the query match.
2. **Compare mixed features** with a weighted distance. For a continuous feature, use `δ = min(|x − q| / τ, 1)`. For a categorical feature, use `δ = 0` if equal and `1` otherwise. Omit missing features and renormalize by the remaining weights:

   ```text
   distance(query, event) =
       Σ(weight_j × δ_j) / Σ(weight_j for observed fields)
   ```

   Suggested starting weights:

   | Feature | Weight |
   |---|---:|
   | Focal player position | 2.0 |
   | Opponent position | 2.0 |
   | Incoming shot source and target | 1.5 |
   | Incoming shot family | 1.25 |
   | Forehand/backhand side | 1.0 |
   | Player velocities | 0.75 |
   | Incoming height | 0.75 |
   | Rally length | 0.5 |
   | Score pressure | 0.5 |

3. **Retrieve the closest 30 eligible events**, then keep only those within a starting distance threshold of `0.30` and with at least 70% of the weighted features observed. These values are adjustable engineering defaults. If there are too few close events, return “no close match” instead of silently widening the search.
4. **Optionally rerank** close events using constrained DTW on reliable pre-contact player trajectories. A starting setup is a 0.75–1.5 second path window and a ±20% alignment band. These values are estimates. Use discrete Fréchet for route shape, such as recovery paths, rather than contact timing.
5. **Keep pose similarity separate.** If the camera view and pose quality are suitable, contact-aligned pose DTW can find similar technique examples after the tactical matches are found. Do not let pose similarity turn a tactically different situation into a match.

The output is a list of event IDs, distances, clip timestamps, confidence, and provenance. This same event record can be the shared data contract for the phone app and the developer API/SDK.

## Recommendation logic

For each close neighbor, group actual actions by shot family and target zone. Start with the focal player’s own history. A point won after a shot is an **observed association**, not proof that the shot caused the win.

For an action with `w` points won out of `n` observed attempts, display the raw count and a smoothed rate:

```text
posterior = Beta(1 + w, 1 + n − w)
```

Use a Wilson interval for a frequentist interval, or show the Beta posterior interval. If comparing two actions, compare their difference directly; do not infer a reliable difference just because their individual intervals look different. Also show distinct rally and match counts, because repeated shots from one rally are not independent evidence.

Evaluate geometry separately from historical outcomes:

```text
reach_margin(target) =
    distance(opponent_position, target)
    − opponent_speed_estimate × ball_flight_time_estimate
```

A positive value only means the target is outside this **simple reach-radius estimate**. It ignores acceleration, direction changes, and shot execution. If flight time or player tracking is unreliable, suppress this score and show only a court-space view. Compute rule legality and distance to lines/net per sport. Keep the UI’s evidence panel split into **Observed outcomes** and **Geometry estimate**, rather than blending them into a precise-looking single score.

Suggested result wording:

- `Observed: 4 of 7 points won in close matches`
- `This is a similar example`, for retrieval alone
- `Geometry estimate: open space on the backhand side`
- `No matched evidence yet`, when there is not enough data

Avoid “optimal,” “will win,” or “weak side” unless the evidence gate is met.

## Evidence and display thresholds

There is no verified universal number of rallies or matches that makes a tactical pattern meaningful. As a statistical reference, for an independent proportion near 50%, roughly 43 observations are needed for a 95% margin of error near ±15 percentage points, and roughly 97 for ±10 points. Repeated events within one rally or match reduce the effective evidence. The following are **product guardrails**, not validated sports-science thresholds.

| Feature | Show | Stronger claim |
|---|---|---|
| Similar clip retrieval | 1 valid event; label it “closest observed” | No recommendation from retrieval alone |
| Early personal action signal | At least 20 close decisions, at least 5 for each compared action, from 3 or more rallies; show counts and intervals | Keep it provisional if all examples come from one match |
| Repeatable personal comparison | Around 50 close decisions, at least 10 per compared action, across 3 or more matches; require a material difference and uncertainty interval that supports it | Still describe association, not causality |
| Opponent placement or shot tendency | At least 10 opportunities in a cell; label “observed in this match” | Around 30–50 opportunities across 3 or more matches before calling it repeatable |
| Pressure pattern | Show counts for pressure and baseline situations separately | Treat as exploratory until there are enough pressure events across multiple matches |
| Technique examples | 1 clean example can be played back | Around 10–20 clean, similarly framed contacts for a within-player summary; do not label it “correct technique” |

For a formal recommendation gate, one reasonable **estimated** rule is: at least 20 close decisions, at least five examples of each compared action, and posterior probability of a lift greater than five percentage points of at least 0.90. Reserve “repeatable” for larger multi-match evidence. If these gates fail, show a similar clip or geometry-only simulation.

## “Better action” clips: provenance and cold start

| Source | Use and label |
|---|---|
| Same player’s prior match clips | Best personalized evidence when participants and recording permissions are clear. Label `OWN HISTORY`; show the outcome and matched count. |
| Teammate/opponent clips in the app | Use only with sharing permission. Label `SHARED PLAYER`. |
| Team-recorded drills | Useful cold-start reference. Label `TEAM REFERENCE`, not “this worked better in your match.” |
| Publicly licensed individual videos | Check the specific file, license, attribution, and relevant participant/privacy rights. CC BY 4.0 permits reuse and adaptation with attribution, a license link, and notice of changes. ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)) |
| Broadcast or dataset video | Do not bundle without direct permission. ShuttleSet’s code license does not establish rights to its underlying match videos. ([ShuttleSet README](https://github.com/wywyWang/CoachAI-Projects/blob/main/ShuttleSet/README.md), [repository license](https://github.com/wywyWang/CoachAI-Projects/blob/main/LICENSE)) |
| Synthetic top-down alternative | Use when no real comparable clip exists. Label `SIMULATED`; never present it as real footage. |

YouTube’s Standard License is the default, and YouTube says uploaders must grant reuse rights themselves; a public video is not automatically a reusable clip. ([YouTube license guidance](https://support.google.com/youtube/answer/2797468)) The sufficiency of releases for participant and venue footage under Indian law is **UNVERIFIED**.

With a first-time user and one match, show the actual point and one nearest personal example as `similar situation`. If the app has a candidate alternative, show the action’s observed count. When no clip supports it, animate the alternative on the top-down court. Do not fabricate a photorealistic “better” clip.

## Opponent analysis

Build conditional distributions such as:

```text
P(opponent shot family, target zone
  | source zone, incoming shot, court position, pressure bin)
```

Useful visual panels:

- Target-zone heatmap by where the opponent hit from
- Forehand/backhand response by incoming zone
- Recovery position after a shot family
- Rally-length distribution
- Pressure-situation distribution beside the overall distribution
- Short sequence view: `your shot → opponent response → next opening`

Show the event count and distinct rallies/matches when a user opens a chart. Describe what was observed. Call a side a “weakness” only if comparable opportunities and outcomes support that statement; a placement heatmap alone may reflect the user’s shot selection.

## Game simulation and replay UX

The proposed single “freeze when the opponent hits, then choose a return” interaction has an information gap: at opponent contact, the user has not yet seen the incoming flight. Split it into two choices:

1. **Read the shot:** optionally freeze shortly before opponent contact and ask the user to predict the incoming direction or zone. Reveal the contact and flight.
2. **Choose the response:** play the incoming shot, then freeze just before the focal player’s contact. Let the user tap or drag a target. Reveal the actual action and point result.

The first freeze is a prototype setting, not a verified optimum. Temporal-occlusion work supports the interaction format, but not a particular freeze time or this app’s scoring. ([Meta-analysis](https://link.springer.com/article/10.1007/s40279-024-02073-6))

For the response choice, display a top-down court with player markers, selectable target zones, line-risk bands, and uncertainty halos. After the choice, show the selected target beside the actual action. If a real matched alternative exists, play that clip. Otherwise show only a 2.5D court animation labelled `SIMULATED`. A full 3D reconstruction from one camera would imply precision about height, spin, and depth that this data may not support; that is an engineering inference.

Post-game screen flow:

1. Import the local video and choose sport, ruleset, singles/doubles, and focal player.
2. Tap court corners and confirm orientation.
3. Review a rally timeline with confidence markers.
4. Open a rally into a side-by-side comparison synced at the contact frame. Link scrubbing, and use a ghost overlay for the court path when it helps.
5. Keep the summary minimal. Make counts, intervals, confidence, and clip source available directly on the evidence card.

## Optional pose model for technique similarity

Pose-DTW is not a good dependency for the core tactic matcher. If tested as a separate close-up technique feature, the current MediaPipe Android guide uses `com.google.mediapipe:tasks-vision:latest.release`; the [official release page](https://github.com/google-ai-edge/mediapipe/releases) lists MediaPipe `v1.1.0` released on October 7, 2026. The Android guide does not pin a version. The current [artifact listing](https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision/versions) includes `1.1.0`, but resolve and smoke-check it before pinning.

| Model bundle | Listed size | Reference speed |
|---|---:|---|
| `pose_landmarker_lite.task` | 3 MB | About 44 FPS CPU or 49 FPS GPU |
| `pose_landmarker_full.task` | 6 MB | About 18 FPS CPU or 40 FPS GPU |
| `pose_landmarker_heavy.task` | 26 MB | About 4 FPS CPU or 19 FPS GPU |

The speed figures are from the model card’s Pixel 3 benchmark, not the team’s phone. The model card lists Apache 2.0 and says the model is for single-person video; multiple people and subjects more than 4 m from the camera are out of scope. That makes it a poor sole pose tracker for a wide, full-court shot. ([Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android), [model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf))

**UNVERIFIED:** performance and tracking accuracy on the team’s Android phone; whether it can extract two-player contacts, ball flight, and landing reliably from venue footage; and the legal sufficiency of event-specific participant and venue permissions. No paid API is required for the matching and recommendation algorithm if the event records and clips are local.

## Recommendation

Build the core around one shared event schema, weighted nearest-neighbor retrieval, outcome counts with uncertainty, and a separate geometry view. Use badminton as the first research-backed tactical schema because ShuttleSet provides detailed public stroke-level annotations. Pick the live demo sport based on the team’s available court and footage. For the demo, use consented local clips and a simulated top-down fallback, so the experience does not depend on venue connectivity.

### Three biggest risks

1. Low-resolution single-camera footage can put the wrong player, contact, or landing point into a match and make the recommendation look plausible but false.
2. Sparse, self-selected examples can make an action or opponent tendency appear stronger than it is.
3. The best comparison clip may be unavailable or lack clear rights, weakening the “show, don’t tell” moment.

### Open questions only the team can answer

1. Which sport and ruleset can you capture and demonstrate most reliably at the venue?
2. Which Android phone and camera position must support the live analysis?
3. Can the team record and retain participant- and venue-approved clips for the demo?