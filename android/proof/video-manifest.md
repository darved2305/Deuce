# Test footage manifest (Person 2, Phase 1)

Videos never enter git (AGENTS.md rule 9). Local copies: `android/test-media/raw/` (gitignored);
on the A55: `Movies/Elevate/`. Measured 2026-10-10 with ffprobe (static build in `~/tools`).
Frame timing: packet PTS of the video stream, intervals in µs. WebM timestamps are 1 ms ticks,
so their intervals are quantised to 1 ms.

| File | Source | Licence | Codec · size · rot | Duration · frames | Frame interval (median / min / max µs) | Timing | sha256 (file) |
|---|---|---|---|---|---|---|---|
| `h13g_singles.mp4` | YouTube `h13gAmp9zJY` (An Badminton), streams per `fixtures/clips.md` | team decision D-261010-HW1 (licence not tracked; source URL is) | H.264 · 1920×1080 · 0 | 484.60 s · 24,227 | 20000 / 20000 / 20000 | CFR 50 fps, 0 gaps | `534e6ff4…68377` |
| `china_open_2025_final.webm` | [Commons](https://commons.wikimedia.org/wiki/File:Wang_Zhiyi_triumph_in_all-Chinese_China_Open_finals_I_China_Open_2025.webm) | CC BY 3.0 | VP9 · 1920×1080 · 0 | 505.83 s · 30,349 | 17000 / 16000 / 17000 | CFR ~60 fps | `2500471d…cbb52` |
| `sport_en_france_4k.webm` | [Commons](https://commons.wikimedia.org/wiki/File:Sport_en_France_-_Maxine_retrouve_Qi_Xuefei,_championne_de_France_de_badminton_!.webm) | CC BY 3.0 | VP9 · 3840×2160 · 0 | 645.22 s · 16,129 | 40000 / 40000 / 40000 | CFR 25 fps | `b67b60e4…5f7` |
| `eurogames_bern_1.webm` | [Commons](https://commons.wikimedia.org/wiki/File:Badmington_competition_EuroGames_Bern_2023.webm) | CC BY-SA 4.0 | VP8 · 848×464 · 0 | 25.60 s · 767 | 33000 / 33000 / 67000 | irregular: 1 dropped-frame gap | `3669343e…786ed` (= `clips.md`) |
| `eurogames_bern_2.webm` | [Commons](https://commons.wikimedia.org/wiki/File:Badmington_Competition_EuroGames_2_2023_in_Wankdork,_Bern.webm) | CC BY-SA 4.0 | VP8 · 848×464 · 0 | 96.90 s · 2,904 | 33000 / 33000 / 67000 | irregular: 3 gaps | `4e5dab1e…0029` |
| `vfr_tabletennis_handheld.webm` | [Commons](https://commons.wikimedia.org/wiki/File:Table_tennis_game.webm) | CC BY-SA 4.0 | VP9 · 1080×1920 portrait · 0 | 21.97 s · 573 | 38000 / 38000 / 39000 | ~26 fps, steady at 1 ms resolution (not truly VFR, despite the file name) | `226250d5…34c6` |

Notes
- `h13g_singles.mp4` = `h13g_singles.v299.mp4` (sha256 `bfb5ebda…91ba`, **matches** `clips.md`) muxed with
  the format-140 audio by `ffmpeg -c copy`. The audio file we fetched is 7,837,994 bytes, sha256
  `5da67e01…9ff2`: **does not match** `clips.md` (7,837,995 bytes, `52582f23…7b7`), one byte off,
  most likely the muxer metadata from a different ffmpeg version. Video frames, and so every label
  timestamp, are unaffected. Reported in `PHASE1_BLOCKERS.md`.
- Mendeley "Video-Based Frame-Level Dataset of Badminton Player Movements" (CC BY 4.0,
  https://data.mendeley.com/datasets/3sp4xntp34/1): portrait phone recordings (1080×1920 and
  2160×3840). Download still in progress; rows get added when it is on disk and probed.

## Coverage against the Phase 1 test plan
| Need | Covered by | Status |
|---|---|---|
| Hand-labelled reference rally | `h13g_singles` 40.00–43.98 s | labels exist (see `PHASE1_STATUS.md`) |
| Landscape badminton | `h13g_singles`, `china_open_2025_final` | on phone |
| Portrait | `vfr_tabletennis_handheld` (table tennis); Mendeley badminton pending | partial |
| Different resolution | `sport_en_france_4k` (4K), `eurogames_*` (848×464) | on phone |
| Irregular frame timing | `eurogames_*` (dropped frames) | on phone |
| True VFR / slow motion | none yet: needs an A55 or S23 FE slow-motion recording | **gap** |
| Unsupported / corrupt media | `corrupt_truncated_h13g.mp4` = first 300,000 bytes of `h13g_singles.mp4` (moov atom cut off) | on phone |
