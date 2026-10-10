# 05 · App experience: screens, visual language, daily loop

Native Android (Kotlin + Jetpack Compose + Media3; `11-debates.md` D1). UI concept: `docs/ui/`
(illustrations stand in for real footage; numbers there are illustrative fixtures).

## 1. The feeling

Opening a match should feel like a broadcast replay of **your** game: dark, video-first, the
ball drawn on your real footage, one clear takeaway, everything else one tap away, and the app
openly saying what it is unsure about. References: F1 TV (video + map), Hawk-Eye landing marks,
WHOOP (one score, tap for contributors), Nothing OS restraint.

## 2. Information architecture

| Tab | Single job | Built |
|---|---|---|
| **Today** | The one next thing: a match ready to review, a drill from your clips, a challenge, or rest | Phase 4 (Phase 2 shows a simple latest-match card) |
| **Matches** | History feed, search chips, collections, compare | core |
| **(+) Add** | Pick from gallery (primary) or record with the capture coach | core |
| **Coach** | Ask your history; answers are clips | Phase 4 |
| **You** | Player card, sharing, model manifest | partly core (manifest) |

Depth rule: summary → rally → evidence → raw detail/corrections; never deeper than 3 taps.

## 3. Core flow

### 3.1 Add a match
- Video plays immediately (local). Sport pills (last used preselected; Free play option).
- Four **named** corner handles (near-left, near-right, far-right, far-left) with a magnifier
  loupe; "which one is you" tap; Free play: freeze and tap the ball.
- "What this clip supports" card from the preflight (resolution, real fps, camera still,
  which features are available/estimated/unavailable).

### 3.2 Timeline from sound (on the phone)
- Audio onsets computed locally produce **rally candidates**: dotted bars labelled "from
  sound" with a counter. Tap a bar to play that part of the local video.
- They are candidates, never confirmed rallies; they turn solid only when the cloud confirms.

### 3.3 Upload and analysis
- Real bytes sent, measured upload rate, ETA from actual throughput, server stages
  (queued, processing window n, finalizing). Never a fake percentage, never a full-screen
  spinner. Progress notification when backgrounded; history stays browsable.

### 3.4 Match overview
- Score (user-confirmed) and result; **hero card**: a real frame with the overlay that proves
  the insight + one caption with its count ("7 of 9 observed clears landed mid-court"); court
  map toggle (landing / coverage / points); rally barcode (bar height = shots, colour = winner).
- If no insight passes its evidence gate, the hero is the best real rally clip instead.

### 3.5 Rally replay
- ExoPlayer on the original with a Media3 frame effect drawing: shuttle trail (observed solid,
  one-slot interpolation dashed, gaps as gaps), landing marks, player foot paths, mini court.
- Flight time tag per shot ("0.42 s to land"); no km/h.
- Frame step with haptic tick; 0.25× / 0.5× / 1× from original frames; "smoothed" slow motion
  only from a server-rendered copy, labelled.
- Evidence chip per event: observed frames, inferred slots, cues.

### 3.6 Compare (signature moment)
- Two **real** clips synced at `contact_ms`; stacked in portrait, side by side in landscape;
  one scrubber. Labels: `THIS RALLY` and the second clip's provenance (`YOUR MATCH · 3 OCT`,
  `SHARED BY …`, `TEAM REFERENCE`) plus its role: **SIMILAR MOMENT** (below the evidence gate)
  or **WORKED FOR YOU · n/N** (above it). `SIMULATED` top-down animation only when no real clip
  exists.
- Descriptive counts with denominators ("in your last 4 matches from this zone: 4 of 5 points
  won"), never "you should".
- A55 fallback if dual decode stutters: one clip plays, the other shows the frame at the same
  relative time.

### 3.7 Review a moment (AI referee)
- Before / impact / after frames; surface map zoomed to the line with the estimated contact
  area; verdict chip **MODEL ESTIMATE: IN / OUT / TOO CLOSE TO CALL / UNOBSERVED**, the margin
  range only when the estimate is not abstaining; "Confirm point" and "Correct" buttons; score
  updates only on confirmation.

### 3.8 Simulation (Phase 4)
- Read: freeze before the opponent's contact, tap where it lands, reveal (exact / adjacent /
  wrong / unscored).
- Decide: freeze before your contact, tap a target zone; show legality and defender spacing
  (covered / adjacent / separated); historical counts only above the gate. Real continuation
  plays afterwards.

### 3.9 Opponent scout (Phase 4)
- Tendency tiles with counts and distinct matches, each opening a reel of real clips.

## 4. Visual system

Tokens (dark-first): Ink `#101519` (background), Surface `#1D272D`, Chalk `#F3F0E8` (text,
court lines), Ice `#62C7D5` (you), Ember `#F39B68` (opponent), Citron `#DCE76B` (the ball and
the single key action). Heat maps: Cividis with counts. Never colour alone (also line style and
markers). Type: IBM Plex Sans (UI, includes Devanagari) and IBM Plex Mono (scores, times,
values). Dot-matrix motif only for score and rally barcode.

Visual primitives (each answers one question and opens real footage): landing map, coverage
map, movement trail, shuttle trail, rally barcode, score worm, synced clip pair, ghost path on
the court map, freeze-frame telestration, uncertainty area, player card, one-number hero with
denominator.

Motion: the signature transition is video → court (the frame's court corners lift and flatten
into the top-down court with the selected shot anchored); court mark → video seek; verdict
"stamp" with a heavy haptic. Respect the system "remove animations" setting.

Haptics: tick (frame snap, zone hover), click (commit), heavy (verdict). Nothing else.

Words: at most one line, generated from structured data, always with a count, always linked
to clips. An LLM never invents a number, ruling or tendency.

## 5. Daily life loop (Phase 4)

| Surface | Shows |
|---|---|
| Glance home widget | One next action with a real poster frame: unreviewed match, drill from your clips, friend's challenge |
| Progress + ready notifications | Upload/analysis stages; "match ready" with the hero frame |
| Local reminders | User-chosen practice time; opt-in "one year ago"; max one non-urgent reminder a day, quiet hours, no guilt copy |
| Calendar | Invite a partner; next session shows on Today |
| Read the Shot challenge | Send a friend a freeze from your real clip; both see the reveal |
| Coach loop | Digest of a few moments per student, comment on a frame, assign a drill, mark practised |
| Weekly rhythm | "3 sessions this week" ring, rest days count, no punishing streak |

## 6. History across many sessions (UX)

Matches feed (date, sport, opponent, result, poster, status); search parsed into editable
chips (`me` `backhand` `error` `close game`) before results; collections of pinned moments;
compare two matches with non-comparable metrics greyed and explained; captions that refer
back ("like 3 Oct") open that exact clip.

## 7. Voice (Phase 4)

Voice query → on-device speech recognizer → the same chip parser → clips. Voice score entry
("point me", "point them", "undo") while recording.

## 8. Offline

Cached history and clips, local playback with cached overlays, score keeping, corrections and
uploads queued; "waiting for connection" state with auto-resume.

## 9. Accessibility

TalkBack labels for every visual (court maps get a spoken summary), text scaling, contrast
≥ 4.5:1 on opaque label backing over video, no colour-only meaning.
