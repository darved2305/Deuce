# Clips · the real footage everyone builds and measures on

Decided in D-261010-HW1. Video never enters git: each clip is pinned here by source, stream IDs
and sha256, and lives in Person 1's Modal Volume `elevate-clips` (and in R2 once the bucket is up).
Licences are not tracked for these clips; the source URL is. If a download's sha256 differs from
the one below, stop and say so in your PR: never swap a clip silently.

## The clips and what each is for
| ID | What | Role | Who may tune on it |
|---|---|---|---|
| `h13g_singles` 31–91 s | Amateur singles, static camera centred behind the baseline, 1920×1080 at 50 fps | **Working clip (dev):** Person 1's first detection runs, Person 2's overlay spike, Person 4's start fixtures | Person 1 |
| `h13g_singles` 300–360 s | Same match, later rallies | **Held-out:** hand-labelled by Person 4, scored only by Person 4's eval | nobody |
| `stdata_test` | TrackNetV3 authors' labelled test split (broadcast badminton, frame-level shuttle labels) | **Method check:** our TrackNetV3 must reproduce the published numbers before anything is tuned | nobody |
| `eurogames_bern_2023` | Doubles from the stands, 848×464 at 30 fps (already in `elevate-clips`, PR #1) | **Low-resolution check** (mentor ask) | Person 1 |

## `h13g_singles`
- Source: "12 year Vs 24 year badminton player challenge match !" by An Badminton, YouTube
  `h13gAmp9zJY` (https://www.youtube.com/watch?v=h13gAmp9zJY), uploaded 2025-04-12.
- Pinned streams (fetched 2026-10-10):
  - video, format 299, H.264 1920×1080 50 fps: 55,643,466 bytes, sha256
    `bfb5ebda742213e225d35135b2d38a28c325f21e4c7510df7b8b201d6c6891ba`
  - audio, format 140, AAC: 7,837,995 bytes, sha256
    `52582f23252205d4bc86133d7391cc996290a1e65a863355f2f921263257f7b7`
- Layout: intro talk until 30.24 s, then continuous play from one fixed camera to the end (484.5 s);
  no scene cuts after 30.24 s. The score is burned in at the top left, so every rally's winner is
  readable from the video itself.
- Known limit: the camera is low and the near baseline appears to be below the frame, so the
  four-corner calibration probably cannot run on this clip (no landing marks or line calls on it;
  the app says so). Person 1 confirms on the first calibration attempt. Calibration is developed on
  `stdata_test` (full court in frame) and on team-recorded footage.
- Fetch (once, then upload to the Volume; everyone else pulls from there):
  ```sh
  yt-dlp -f 299 -o h13g_singles.v299.mp4 https://www.youtube.com/watch?v=h13gAmp9zJY
  yt-dlp -f 140 -o h13g_singles.a140.m4a https://www.youtube.com/watch?v=h13gAmp9zJY
  shasum -a 256 h13g_singles.v299.mp4 h13g_singles.a140.m4a   # must match the hashes above
  ffmpeg -i h13g_singles.v299.mp4 -i h13g_singles.a140.m4a -map 0:v -map 1:a -c copy h13g_singles.mp4
  modal volume put elevate-clips h13g_singles.mp4
  ```
  Process segments by time range on the full file; do not re-encode a cut (re-encoding changes the
  real timestamps the engine measures).
- First look (2026-10-10, local probe, official TrackNetV3 weights, whole frame resized without the
  court crop the plan requires, so not the engine's method): candidates in 501 of 600 frames over
  12 s of the working segment; by eye on 20 sampled frames most airborne candidates sit on the
  shuttle and a few near players' feet are false positives. This is candidate coverage, not
  accuracy. The same probe found candidates in 106/450 and 146/348 frames on the EuroGames clips.

## `stdata_test` · Shuttlecock Trajectory Dataset, test split
- Source: https://hackmd.io/Nf8Rh1NrSrqNUzmO0sQKZw (TrackNet/TrackNetV2 authors, NYCU). The
  TrackNetV3 README reports its numbers on this split: accuracy 97.51%, precision 97.79%,
  recall 99.33%, F1 98.56%.
- The zip is 1,731,293,072 bytes. The share link needs a guest cookie first. Download it inside a
  Modal function straight into a Volume, not on a laptop:
  ```sh
  U='https://nycu1-my.sharepoint.com/:u:/g/personal/tik_m365_nycu_edu_tw/EWisYhAiai9Ju7L-tQp0ykEBZJd9VQkKqsFrjcqqYIDP-g?e=S0AB1Z'
  curl -sL -c ck -b ck -o /dev/null "$U"
  curl -L -c ck -b ck -o TrackNetV2.zip "$U&download=1"
  ```
  sha256 `d4e69cc350b64256b269317da1f7cd0ce6058babb5bde72a4160d7e339b6f0b1` (recorded by Person 1,
  PR #9). Person 1 reproduced the published numbers on a T4: accuracy 97.518%, F1 98.561%.
- Use: (1) run the official `test.py` from the TrackNetV3 repo to reproduce the README numbers
  (proves weights and data are right); (2) run our engine's TrackNetV3 module on the same split and
  match the official run. TrackNetV3 was trained on this dataset's train split, so this checks our
  port, not how well it works on phone footage. Broadcast footage: engine evaluation only, never
  shown in the app or the demo.

## `eurogames_bern_2023`
https://commons.wikimedia.org/wiki/File:Badmington_competition_EuroGames_Bern_2023.webm, sha256
`3669343e1837befefe9f77ae3b6a740767e4b6e59054d54b9cbbac1042d786ed` (PR #1).
