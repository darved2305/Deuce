# Set up analysis flow (G1, slice 2) · integration note

Sport → court corners (free play: the ball) → who is you → review, tapped on a real still frame of
the picked video. Produces the session fields this flow owns in `POST /v1/sessions`
(contracts §2): `sport`, `format`, `ruleset`, `mode`, `calibration_input`, `who_is_me`,
`free_play_seed`. The caller adds `source`, `rally_candidates`, `upload_mode`.

## Wiring (for the lead)

```kotlin
@Composable
fun SetupFlowScreen(
    uri: Uri,               // the picked video (same grant as playback)
    facts: SourceFacts,     // PreflightState.Done.facts: display size = image space, duration clamps frameUs
    frameUs: Long,          // frame to tap on, e.g. controller.positionMs() * 1000 when the user taps "Set up"
    onCancel: () -> Unit,   // back on step 1, the Cancel button, or a frame that can't be read
    onDone: (SetupResult) -> Unit,
)
```

- Package `com.elevate.app.ui.setup`. Owns its `SetupViewModel` via `viewModel(key = "setup:$uri@$frameUs")`
  (activity store, so it survives rotation; its frame bitmap, up to 1920 px long side, stays in
  memory until the activity is destroyed or the key changes).
- Only call it once preflight is `Done` and not `blocked`. Pause playback first (the frame is a
  still; the player is not touched).
- `onDone` yields `SetupResult`; `result.toJson()` is a `JsonObject` with exactly those seven keys
  (`calibration_input` / `free_play_seed` as JSON `null` when absent, both required by the schema).
  Merge it with `source`, `rally_candidates`, `upload_mode` for the request body.
- Draws edge to edge with `safeDrawingPadding()`, portrait and landscape (360 dp side panel).
  System back steps back, then cancels.

## What it does
- **FrameGrabber** (`data/media/FrameGrabber.kt`): `MediaMetadataRetriever`, `OPTION_CLOSEST`
  then `OPTION_PREVIOUS_SYNC` on null; `getScaledFrameAtTime` (API 27+, square box = long-side cap),
  else `getFrameAtTime` + downscale; IO dispatcher; retriever always released; null/exceptions →
  `FrameGrab.Failed(code)`. The UI then shows "Couldn't read a still frame from this video" with
  Cancel, never a blank canvas. `frame_ms` = PTS of the grabbed frame read from the sample table
  (`MediaExtractor`: nearest sample between the previous and next sync frames; for the sync
  fallback, the previous sync frame).
- **SetupState / reduce** (`ui/setup/SetupModel.kt`, pure Kotlin): sport pills (badminton,
  table tennis, tennis, free play; squash/padel/pickleball not listed, §9); only badminton is
  offered ("not in this build" for the rest); singles/doubles ↔ template `badminton_singles` /
  `badminton_doubles`; match/practice; free play ⇒ format `none`, mode `free_play`, no court step,
  ball tap → `free_play_seed`. Corners: taps place the next unplaced corner in contract order;
  drags move a placed corner (one undo step per drag); undo stack; Reset is undoable; changing
  sport or format clears the corners. Next is gated by `CourtGeometry.check`, and its `Problem`
  text is shown. Points are clamped to the frame and rounded to 0.1 px.
- **Ruleset**: `{"id":"bwf_3x21","version":"2025-04"}` for badminton (contracts §2). §10 names
  `ittf_11`, `tennis_bo3_tb`, `free_play` but no version, and `packages/rules` has none yet, so
  those sports are not offered rather than sending an invented version.
- **UI** (`ui/setup/SetupFlowScreen.kt`): frame drawn with fit scaling via `FitTransform`; named,
  coloured handles (32 dp grab radius = 64 dp hit area; labels are on-screen text with TalkBack
  descriptions); 3× loupe drawn from the frame bitmap in a circle above the finger (below it near
  the top edge) with a crosshair; taps in letterbox bars are ignored; who-is-me / ball markers;
  review card lists sport · format · mode, rules, corners n of 4 + template, "You marked at t".

## Verified (this machine, this session)
- `./gradlew assembleDebug testDebugUnitTest lintDebug`: PASS. 83 unit tests (69 before +
  10 `SetupReducerTest` + 4 `SetupResultJsonTest`), lint 0 errors (2 known warnings).
- `SetupResultJsonTest` validates `toJson()` against
  `packages/contracts/schemas/session.create.request.v1.schema.json` (only `source`,
  `rally_candidates`, `upload_mode` dropped from `required`), incl. negative cases.

## UNVERIFIED (not run on a phone in this slice)
- Everything on device: gestures, loupe, layouts, TalkBack, and the screen itself (no adb used).
- `getScaledFrameAtTime` output orientation/size on the A55 (only `getFrameAtTime` is proven
  upright by `FrameGrabRotationTest`); the square box is chosen so either orientation is capped.
- That the extractor's nearest sample is exactly the frame `OPTION_CLOSEST` decodes.
- `frame_ms` uses the raw sample PTS, like `TimeMapping.presentationToSourceUs` with offset 0; a
  file whose first presented frame is not at 0 would be offset by that amount.
- If the sample table can't be read, `frame_ms` falls back to the requested time.
- The Dolby Vision clip that returned null for `OPTION_CLOSEST`: whether `OPTION_PREVIOUS_SYNC`
  succeeds there is unknown; either way the failure screen is shown, never a blank frame.

## Known limits / deferred
- Free play is implemented and tested in the reducer but hidden until a `free_play` ruleset
  version is pinned; its `calibration_input` floor rectangle is not built (sent as null).
- Who-is-me and the free-play ball are tapped on the same frame as the corners (`t_ms = frame_ms`);
  no frame stepping inside the flow yet.
- Last-used sport is not remembered yet (05 §3.1 asks for it; needs persistence).
- The "what this clip supports" card stays on the replay screen's preflight.
