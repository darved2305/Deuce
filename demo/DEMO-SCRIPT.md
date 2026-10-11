# Demo script (3 minutes, recorded on a Samsung Galaxy A55)

Recording slot 07:10. Everything on screen is real footage and real engine output. Nothing is
sped up or cached unless it is labelled "analysed earlier".

## Before you press record
- Container warm: the engine must have been started by the 06:55 run so the first analysis does not
  cold-start (a cold start took about 2 minutes on a 25 s clip, issue #67).
- Demo clip on the phone: `h13g_singles.mp4` (484.5 s, 1080p, 50 fps, amateur singles from one fixed
  camera behind the baseline; `fixtures/clips.md`). Why this clip: it is our real user's footage, not
  TV broadcast, and every layer is proven on it. Shuttle tracking was checked against hand labels
  (PR #49), all 81 chunks of a real A55 upload were analysed (PR #54), hits were detected (PR #76),
  and the court was calibrated from real taps (PR #87), with the app and the engine agreeing within
  1 cm (PR #88). The score is burned into the video, so anyone can check rally winners by eye.
- Live: a fresh upload of this clip. The full analysis takes minutes, so the demo shows the first
  chunks streaming in, which is the point: results arrive while the rest is still processing.
- Analysed earlier: the same clip's completed run `a_73b8c407edb6440a953f0204796370e6` (81 chunks,
  PR #54), real corner taps from session `s_3df758f240434f74820e829033966138` (issue #61). Same video,
  so live and complete read as one story.
- Backup moment only: `eurogames_bern_1.webm` (848x464 doubles from the stands, PR #74), to show it
  still works at low resolution.
- Wi-Fi on, notifications off, battery above 50%, screen recording on, phone in portrait.

## Beats

| Time | Beat | Tap | Say (one line) | Judge should notice |
|---|---|---|---|---|
| 0:00-0:15 | Hook | Home screen | "Pros have Hawk-Eye. Clubs have a phone. We rebuilt what we honestly can from one phone camera, with no model training." | Nothing is pre-loaded; the app starts empty. |
| 0:15-0:35 | Pick a video | Pick video, choose `h13g_singles.mp4` | "I pick a match from my gallery." | The video plays immediately. A clip check shows real frame timing; rally candidates appear "from sound" on the timeline, dotted. |
| 0:35-1:05 | Set up the court | Tap the four court corners with the magnifier; drag one corner past the frame edge; Looks right; choose who you are; Analyse match | "Four taps tell the engine where the court is. A corner can sit outside the picture, and the app marks it as estimated." | Dashed ring and an "off-frame" tag on the outside corner; the review step counts it. |
| 1:05-1:50 | Live analysis | Stay on the Analysing screen, then open the video from the status pill | "The video goes to private cloud storage, then to GPUs. Results stream back every few seconds of footage." | Real stages and real bytes ("Sending your video", measured rate), never a fake percentage. Chunks complete one by one ("n of 81"), each about 6 s of footage, on up to 10 GPUs in parallel. The yellow shuttle trail is drawn on the real frames of the chunks already done. |
| 1:50-2:25 | Hits and court | Open the overview, then the replay; toggle Court on | "Hits come from sound plus the shuttle's trajectory. The court lines and the top-down map come from those four taps." | Hit ticks and dotted rally spans labelled "candidates", with a count of chunks analysed out of the total. Court lines on the video, a mini court beside it. |
| 2:25-2:45 | It admits what it does not know | Pause on a frame where the shuttle is hidden | "Where the shuttle is not visible we show nothing. No landings or line calls appear until the evidence supports them." | The trail has gaps. There is no invented marker. |
| 2:45-3:00 | Close | Home screen | "Scoring follows the BWF rules, 45 law-cited test vectors shared by server and app. Tracking is 100% precision and 79.7% recall on hand-labelled frames. Cloud cost stays inside the free tier." | The numbers have denominators and links in the README. |

## If the live run is slow

Trigger: the Analysing screen has not shown the first completed chunk by 1:30, or the upload stalls.
At 1:50 in any case, switch to the completed run, because the full 81 chunks take minutes.
1. Say: "That one is still processing on the cloud; here is the same match we analysed earlier."
2. Open the analysed-earlier result of `h13g_singles.mp4` (the fallback approved on issue #67). Say aloud that it was
   "analysed earlier"; never describe it as live.
3. Continue from the 1:50 beat: hits timeline, court lines from the stored real taps, trail on the
   real video.
4. If the upload itself fails on Wi-Fi, switch the phone to the other network or hotspot, tap
   Retry; the upload resumes from the confirmed parts (the app keeps its state on disk).

## Things not to claim
- No landings, IN/OUT calls or score from this live run; those need the closing step that is still
  in progress (issues #79, #82).
- Hit and rally markers are candidates, checked by eye, not a measured accuracy.
- Court quality on the real taps is grade B, not A (issue #61).
- Court lines and the mini court are merged and unit-tested (PR #78); confirm them on the A55 during
  the 06:40 device check (issue #84) before recording, and drop that beat if they do not draw.
