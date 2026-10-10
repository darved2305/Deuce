# Footage sources (all trimmed with ffmpeg, libx264 crf14, no frame-rate resampling; audio dropped)

| dir | clip | source | licence | native fps / size |
|---|---|---|---|---|
| tennis_artlabss | clip.mp4 (first 15 s of video_input2.mp4) | https://github.com/ArtLabss/tennis-tracking/blob/main/VideoInput/video_input2.mp4 (ATP broadcast footage, elevated static camera) | repo licence Unlicense (broadcast content copyright is the owner's; research use only) | 60 fps 1920x1080 |
| badminton_eurogames | clip.mp4 (5-20 s), clip_rally.mp4 (14-26 s) | https://commons.wikimedia.org/wiki/File:Badmington_competition_EuroGames_Bern_2023.webm | CC BY-SA 4.0 | 30 fps 848x464 |
| tabletennis_game | clip.mp4 (2-17 s) | https://commons.wikimedia.org/wiki/File:Table_tennis_game.webm (handheld phone, vertical) | CC BY-SA 4.0 | ~26 fps (VFR) 1080x1920 |
| tennis_kudrinskaya | clip.mp4 (0-15 s) | https://commons.wikimedia.org/wiki/File:Kudrinskaya_Square_Building_game_tennis_summer_in_moscow_2025.webm (handheld phone, vertical, through fence) | CC BY 4.0 | ~29.8 fps 478x850 |

Pexels/Pixabay were blocked (Cloudflare 403 to curl), so Wikimedia Commons was used. ArtLabss video_input5/8.mp4 are also in tennis_artlabss (unused).

## Static-phone follow-up probe
| dir | clip | source | licence | native fps / size |
|---|---|---|---|---|
| mend_smash7, mend_smash4, mend_smash6, mend_clear2, mend_clear3, mend_clear5, mend_defence4, mend_defence5, mend_defence6 | raw.mp4 (single-stroke clips, 1-1.9 s, extracted individually by HTTP range from the dataset zip); clip.mp4 = upright re-encode | https://data.mendeley.com/datasets/3sp4xntp34/1 (Dataset/Forehand_Smash (4,6,7), Backhand_clear (2,3,5), Defence (4,5,6)) | CC BY 4.0 | 30 or 29.97 fps; 2160x3840 (smash, clear2/3, defence4) or 1080x1920 portrait |
| catch_kids | clip.mp4 (full 4.5 s) | https://commons.wikimedia.org/wiki/File:Kidsplayingcatch-japan-2013-dec4.webm (static camera, children throwing a red ball) | CC BY-SA 4.0 | 29.97 fps 1920x1080 |
