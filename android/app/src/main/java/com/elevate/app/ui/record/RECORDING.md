# Record live in Elevate

Film the match inside the app. The phone records rolling pieces of about 6 seconds, each piece
goes to the server while the camera is still running, and the analysis starts after the first
pieces are stored, before you press Stop. The Analysing and Overview screens then take over.

Code: `data/record/` (camera, pieces, upload, gates) and `ui/record/` (screen, view model, wording).
Wiring outside those two folders: a "Record a match" button on Home (`ImportScreen`, `ElevateApp`),
a still-frame variant of the setup flow (`SetupFlowScreen`, `SetupViewModel`), the CameraX
dependencies (`libs.versions.toml`, `app/build.gradle.kts`) and the camera and microphone
permissions in the manifest.

## Parameters and why

Every value is chosen in `RecordingParamsChooser`, `SegmentPlanner`, `RecordGates` or
`CameraSession`, and unit-tested where it is a decision.

| Parameter | Choice | Reason |
|---|---|---|
| Resolution | 1080p (1920x1080). Falls back to 720p, then 480p. Never above 1080p. | The tracking model reads 512x288, so 1080p already gives it 3.75x more pixels than it uses on each axis; 4K only costs bandwidth, storage and the R2 budget. 1080p keeps the shuttle (a few pixels wide) visible after the downscale. |
| Frame rate | 60 fps when the phone offers it, else 30, else its best rate below 30. Requested as a fixed range (60..60) so the encoder does not drift to a variable rate in dim light. | A fast shuttle moves far between frames: 60 fps halves that gap. The engine reads real frame times, so any steady rate is handled. A fixed range avoids a variable-rate file, which the engine flags. |
| Codec | H.264 (`avc1`) in mp4, the CameraX default. | Decodes everywhere, on Modal with PyAV and in the phone's own players. HEVC saves bytes but costs decode compatibility. |
| Bitrate | Chosen from the 300 MiB per-video budget (G1) and the length wanted (default 8 minutes). About 4.3 Mbps video at the default for 1080p; between a floor and a ceiling: 1080p60 4 to 12 Mbps, 1080p30 3 to 8, 720p60 2.5 to 7, 720p30 2 to 5, 480p 1.2 to 2.5. Audio 128 kbps. | 90 percent of the cap is planned; container overhead (about 1 percent) and encoder rate drift take the rest, so the 300 MiB limit is never crossed. Below the floor the shuttle smears; above the ceiling only bandwidth is spent. The screen shows "1080p, 60 fps, up to N min": N is what the cap allows at the chosen bitrate. |
| Hard stops | The recorder gets a per-file size limit equal to what is left of the budget (minus 4 MiB), and recording stops by itself at the budget or the maximum duration. | The server refuses a video over 300 MiB in total (413). Stopping first keeps the recording valid and uploaded. |
| Stabilisation | Optical (OIS) on when the lens has it; electronic (EIS) off. | EIS crops and warps the picture differently over time, which moves the court after its four corners were tapped and breaks the court fit. OIS moves the lens, not the pixels. Handheld shake is handled by the engine's own stabiliser, which only switches on when it measures shake. |
| HDR | Off (SDR only). | The overlays and the effect pipeline do not support HDR, and HDR files are refused for overlays. |
| Exposure, white balance, focus | Locked once, when you tap "Mark the court": one focus and metering run on the middle of the picture, then auto exposure lock, auto white balance lock and focus mode auto (lens stays put). | Exposure pumping and focus hunting change the picture while players cross the frame, which hurts shuttle detection. The gym light does not change during a match. |
| Orientation | Landscape, locked while this screen is open (the app restores its own setting after). | The court is wide; the engine and the corner taps expect an upright landscape frame. |
| Audio | On, and required: the permission card asks for the microphone with the camera. | Hit detection uses the sound of each shot. |
| Court framing guide | A dashed trapezoid with a net line over the live picture and the text "Stand behind the baseline. Whole court in view." | Puts the whole court in view from behind a baseline, where all four corners and both players are visible. A guide for placing the phone only; the corners are tapped on the real picture afterwards. |
| Level indicator | A line that turns green within 1.5 degrees and amber within 3, from the accelerometer. | The accelerometer needs no permission, so it is on. A tilted camera skews the court. |
| Battery | Warn below 20 percent when not charging, block starting at 8 percent or less, stop (and save) at 4 percent or less. Charging never limits. | Warn early enough to plug in; stop before the phone dies so the recording and its pieces stay valid. |
| Heat | `PowerManager` thermal status: warn at moderate, block starting at severe, stop (and save) at severe or worse while recording. | At severe the OS starts throttling the camera and encoder, and may cut the recording abruptly. Stopping first keeps the file valid. |
| Storage | Before recording: free space must cover the pieces plus the joined local copy plus 100 MiB. | Both the pieces and the joined copy exist at the same time at the end. |
| Network | Default: send only on Wi-Fi. A switch allows mobile data. Up to 2 pieces in flight, from the server's `max_concurrency` (never more than 2). Offline or waiting for Wi-Fi pauses sending without using retries; pieces stay on the phone. | A match can be 270 MB, which is a lot of mobile data. Pausing does not lose anything, and sending resumes by itself when the connection returns. |
| Retries | At most 4 tries per piece (the first try and 3 retries, G11) with growing waits. The same upload link is reused for every retry; a new link is asked for only after a refusal or expiry, at most 3 links per piece (the server's G4 limit). | Every use of a link is a billed R2 operation (`docs/ops/cloudflare-budget.md`). After the tries are used, sending stops with a "Try sending again" button. G11 in the budget doc says 3 retries, so 4 tries in total. |
| Piece length | About 6 s, the engine's window length. | The engine decodes 6 s windows; each piece is short enough to upload fast and long enough to keep requests few (an upload link per piece is a billed operation). Pieces are about 3 MB at 4.3 Mbps, far below the server's per-piece limit. |

## Flow

1. Home has a "Record a match" button. Tapping it asks for the camera and microphone and locks landscape.
2. **Framing.** Live preview with the court guide, the level line, the settings line and any battery, heat or storage message.
3. **Mark the court** (setup before recording; this is the choice made). The app locks focus and exposure,
   captures one still of the live picture scaled to the recording's exact size, and opens the normal setup flow on
   it (four court corners, who you are). Reason: the analysis starts after the first piece, so the court corners and
   "who you are" must already exist when the session is created; doing setup after recording would hold the analysis
   until you stop. The cost: the phone must not move between marking and recording, and you stand on court when the
   still is captured. The still stands for time 0 of the recording.
4. **Record.** The session is created with `upload_mode: "segments"`. CameraX VideoCapture writes a piece, and the moment
   it is finalised the next one starts. The screen shows elapsed time, the size so far against the 300 MB budget, and a live
   line such as "3 pieces sent, analysis started" (counts the server confirmed).
5. Each piece: register (`POST /v1/sessions/{id}/segments`) in order, PUT to the presigned URL, then
   `POST /v1/sessions/{id}/segments/{seq}/complete`. Up to 2 pieces upload at once.
6. **Analysis starts early.** Server flow (`routes/analyses.ts`): `POST /v1/analyses` needs piece 0 completed
   (else 409 `upload_not_complete`), then dispatches the engine with piece 0's key; the engine polls the stored pieces
   until the one marked `final`. The app calls it as soon as piece 0 is stored, while recording goes on.
7. **Stop.** The open piece is finalised and sent with `final: true`. The pieces are joined into one local file. When the local
   copy exists and the analysis has started, the match is added to history and the Analysing screen opens (the Overview
   follows as usual).

### The last piece and `final`

`final` is part of a piece's registration and cannot be changed afterwards (409). The last piece is only known at Stop.
So each piece is held back until the next one has finished, or Stop arrives. The cost is one piece (about 6 s) of delay
before upload and before the analysis starts. The benefit is that `final` is always right, even when the recording ends on an
empty tail. A one-piece recording is sent as piece 0 with `final: true`.

## Local copy

The pieces are joined on the phone into one mp4 with a muxer pass over the encoded samples (no re-encoding, so no quality loss
and it takes seconds). Each piece is moved onto one continuous timeline made from the real frame times read from the finished
files (`SegmentPlanner.timeline`), the same timeline sent as `pts_start_us` and `pts_end_us`. So overlays drawn on the local copy
line up with the server's windows. The alternative, a parallel full-length file, is not possible with one CameraX `VideoCapture` (one
recording at a time per use case) and a second encode would double the load on a phone that must stay cool. The pieces are deleted
once all of them are stored on the server.

## Session fields known before the first frame

`source` in `POST /v1/sessions` normally describes a finished file. Here it is the recording plan: the size, rate and codec asked of
the camera, the longest length and most bytes the settings allow, and a fingerprint of the plan as `sha256` (the contract needs 64
hex characters; each piece carries the real sha256 of its bytes). The engine reads the real frame times from the pieces. No route
updates `source` later, so the plan values stay on the session.

## Verified and not verified

Verified (JVM unit tests with fakes, and a full Gradle check):
- Parameter choice: fps fallback, resolution fallback, bitrate floor and ceiling, the budget and duration estimate.
- The piece planner: durations, sizes against the 300 MiB budget, stop reasons, the continuous timeline.
- The upload state machine against fakes of the segment routes and R2: register in order, upload, complete, `final`, analysis
  after piece 0, retries and their limit, link reuse, 403 and expiry, the 3-link limit, offline pause without using tries, resume,
  concurrency limit.
- Battery, heat and storage decisions; the network policy; the level maths; the wording.
- The code compiles against CameraX 1.6.2 and passes lint.

Not verified (nothing was run on a phone or against the deployed server in this slice):
- Everything on a device: the camera opening, the 60 fps request being honoured, 1080p at the chosen bitrate, OIS and EIS settings
  taking effect, the exposure and focus lock holding, the preview picture matching the recorded frame, the level readings and the guide.
- **Continuity between pieces.** CameraX restarts its muxer for each piece, so a gap of some frames between two pieces is possible
  (not measured). The timeline here is continuous and ignores any gap. If a device loses more than a frame or two, the shuttle
  jumps at piece boundaries; the fix would be one continuous encoder with a fragmented muxer.
- The segment routes on the deployed server: the code follows the contract and the merged route source
  (`supabase/functions/api/routes/sessions.ts`, `analyses.ts`, migration `20261011152200_segments.sql`) but was not called live.
- Whether the declared 48 kHz audio rate matches the microphone track of every phone.
- Picture size taken from the preview scaled to the recording size (the preview and the video share one view port so the framing
  is the same; not checked on a device).
- Recovery after the app process is killed mid-recording or mid-upload: pieces already on disk stay, but there is no saved upload
  state, so a killed app does not resume the upload by itself.

## Risks and follow-ups

- Setup before recording means a moved phone invalidates the corners. The "Mark the court again" button redoes it before recording.
- Sending needs the app in the foreground for now (view model scope). A background upload service for the tail after Stop is a follow-up.
- Pieces are not seamless; see above.
