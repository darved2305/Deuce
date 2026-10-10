# 01 · PS 02 fundamentals checklist

Mentors: "PS fundamentals first, then extra features." This file is the contract with the
problem statement. Each row quotes the PS text it answers, our answer, its **build tier**,
and an acceptance test with a named clip, ground truth and pass threshold. Results are recorded
as `pass`, `unmet`, `unknown` or `could-not-run`; a demo is never a pass on its own.

Tiers:

| Tier | Meaning |
|---|---|
| **Core** | Built; passes its acceptance test on held-out footage; in the demo. |
| **Assisted** | Built; needs one human confirmation (a tap) and says so. |
| **Experimental** | Built; output labelled as an estimate; never changes official data (score) by itself. Promoted only by the held-out eval. |
| **Designed** | Specified, with a data model and API contract; built in Phase 4 or later. |

A tier is the **target**. Each row's verified **status** starts as `unknown` and changes only
through `fixtures/eval/RESULTS.md` (`pass`, `unmet`, `could-not-run`).

Held-out eval set (Person 4, `fixtures/eval/`): clips nobody tunes on, hand-labelled ball
positions, hits, landings/bounces with the true side of the line, and rallies with winners.
"Held-out" means the engine's thresholds were not tuned on these clips.

## A. Hard constraints (every feature inherits these)

| ID | PS text | Our answer | Acceptance test |
|---|---|---|---|
| C1 | "work across racket sports … court dimensions, scoring rules, and the size and speed of the ball or shuttle" | A sport is a versioned **sport pack** (`contracts.md` §9): court templates, rules, ball profile, event vocabulary, zone grid. Built: badminton (primary), table tennis (court + rules, no tracker), tennis (court + rules, static-view tracker), free play. Squash, padel, pickleball are listed and visibly unsupported until their packs exist. This is a deliberate partial answer to a six-sport problem. | Same build runs the badminton and table-tennis packs on held-out clips; per-sport results recorded in the matrix. |
| C2 | "using only one normal camera such as a phone" | Phone video is the only sensor. Clip audit records device, resolution, fps, real timestamps. | Every result shows its source clip metadata. |
| C3 | "No training … without collecting labelled data or training or fine-tuning models" | Frozen public checkpoints (named, hashed, with the sports their authors trained on) + geometry, physics, audio, rules. Four corner taps and one ball tap are configuration. | Model manifest screen; a clip filmed at the venue analysed by the same build. |

## B. The match itself

| ID | PS text | Our answer | Tier | Acceptance test |
|---|---|---|---|---|
| T1 | "little or no structured digital record" | Every analysis produces one `match.v1` record (rallies, events, score, players, clips) | Core | Record validates against the schema; API returns it by ID; after the user links an identity, history survives reinstall. |
| T2 | "Scores may be inconsistently maintained" | Rules engine per sport; proposed point winner; one tap confirms; append-only corrections | Assisted | 10 labelled rallies: final score equals hand-kept score after confirmations (pass = exact). |
| T3 | "ball or shuttle speed … unavailable" | Flight **time** per shot (hit to landing, timestamps) for every observed shot; metric speed withheld until calibrated; free play shows image-space px/s labelled as such | Experimental | Flight times within ±1 frame of hand labels on 20 shots. |
| T4 | "… and trajectory are unavailable" | Ball/shuttle trail drawn on the real video; observed solid, ≤1-slot gap dashed, longer gaps shown as gaps | Experimental → Core if eval passes | Point precision ≥ 80% and recall ≥ 50% on 200 labelled positions (badminton, static elevated phone). |
| T5 | "player statistics are scattered" | Per-match player stats from one record (rally lengths, court coverage, landing zones), each with denominator and evidence link | Assisted (court metrics need calibration tier A) | Every displayed number opens its frames; coverage map matches hand-marked positions within 0.5 m on 20 samples. |
| T6 | "disputes can be difficult to resolve" | Review flow: evidence frames + surface map + **model estimate** IN/OUT/TOO CLOSE/UNOBSERVED; human confirms | Assisted (estimate Experimental) | Labelled bounces with A55 slow-motion truth: pass only with zero wrong IN/OUT calls **and** call coverage at or above a threshold the team sets before looking at results; wrong calls, coverage and abstention rate always reported. |
| T7 | "historical match information is rarely preserved in a usable form" | Persistent history across sessions with search and comparability tiers | Core | Search returns clips from ≥2 matches; deleting a match removes its clips everywhere. |

## C. Beyond the match

| ID | PS text | Our answer | Tier |
|---|---|---|---|
| P1 | "Player profiles" | Player card from evidence-backed numbers + self-declared info | Assisted |
| P2 | "performance history" | Session timeline; trends drawn only across comparable sessions | Core (timeline) / Designed (trends) |
| P3 | "rankings" | Club/event rating from confirmed results (Elo/Glicko, no ML) | Designed |
| P4 | "development data" | Recurring patterns + drills linked to clips, behind the evidence gate | Designed |
| P5 | "understand their performance" | Visual overview, one hero insight on real footage, ≤1-line captions | Core |
| P6 | "compare themselves with others" | Opt-in comparisons on comparable metrics only | Designed |
| P7 | "find suitable opponents" | Matching on sport, level, location, availability | Designed |
| P8 | "organise meetups" | Invite → calendar event → shared session | Designed |
| P9 | "discover tournaments" | Organiser API publishes events; app lists them | Designed |
| P10 | "find and book courts" | Venue directory + deep link to the venue's booking | Designed |
| P11 | "Coaches lack continuous data" | Coach view: shared moments, comment on a frame, assign a drill | Designed |
| P12 | "local racket-sports communities remain disconnected" | Club space with members, events, shared leaderboard | Designed |

## D. Intelligence

| ID | PS text | Our answer | Tier | Acceptance test |
|---|---|---|---|---|
| I1 | "analysing matches" | Cloud pipeline, results appear as windows complete | Core | Real stages (upload, queued, processing, finalizing, completed) shown; the end-to-end run of the demo clip over 5G is measured and recorded. |
| I2 | "learning from historical performance" | Similar moments across sessions; "worked for you" only above the gate | Assisted | A comparison opens a real clip from an earlier match with provenance. |
| I3 | "generating predictions" | Excluded from this build: no win probabilities until a model is defined and evaluated on held-out matches. Opponent tendencies (descriptive, gated) in Phase 4. | Designed | — |
| I4 | "personalised coaching and feedback" | Real-clip comparisons with descriptive counts; advice gated | Assisted | Every comparison shows source, rights and n. |
| I5 | "through mobile" | Native Android app | Core | Release APK installed on S23 FE and A55. |
| I6 | "offline" | Cached history and clips, local playback with cached overlays, score keeping, queued resumable upload. New analysis needs a network (cloud engine). | Assisted | Airplane mode: review a past match, keep score, queue an upload that resumes later. |
| I7 | "voice-based experiences" | Voice query → chips → clips; voice score entry | Designed |

## E. Bonus

| ID | PS text | Our answer | Tier | Acceptance test |
|---|---|---|---|---|
| B1 | "SDK and API so that developers, academies and tournament organisers can build on them" | REST (OpenAPI 3.0.4), TypeScript SDK, read-only MCP, minimal embed — same record and IDs as the app | Core (thin slice) | A separate web page built only on the SDK shows the same match; MCP answers a question with a moment link. |
| B2 | "generalises beyond racket sports" | Shared ingestion + **assisted** tracking (one tap, TAPIR) on a non-racket clip filmed at the venue. This shows the pipeline handles new footage with one tap of configuration; it does not claim automatic cross-sport generalisation. | Experimental | Trail drawn on the venue clip; abstains visibly when the ball is lost. |

## F. The core question

> "How much of a professional tracking and intelligence system can be rebuilt from one normal
> camera, across racket sports and without any training, while managing and connecting players?"

We answer "how much" with the capability matrix filled from the held-out eval, shown on screen.
