# 07:00 to 08:00 checklist (submission at 08:00 IST)

Tick each box only after you have seen it happen. Owners in brackets.

## 07:00 Freeze
- [ ] Say "freeze" in the team chat: no more merges to `main` except fixes to a broken demo. [@HetanshWaghela]
- [ ] `main` CI green: check the latest run of `ci-gate`. [@HetanshWaghela]
- [ ] Note the final `main` commit hash here: `________` (the APK and the README must match it).
- [ ] The APK installed on the A55 is built from that hash (rebuild from that hash if unsure). [@darved2305]

## 07:00 Warm up (issue #81)
- [ ] Engine container warm from 06:55 (`min_containers=1`). Set back to 0 right after the recording. [@VrajVed]
- [ ] Three back-to-back runs of the demo clip finished, with analysis id, wall time and chunk count posted on issue #81. [@VrajVed]
- [ ] Live API health answers: `curl https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api/v1/health` returns `{"status":"ok"}`. [@nikhilpravinpise]
- [ ] Storage kill switch is not tripped (an upload is accepted; guards in `docs/ops/cloudflare-budget.md`). [@nikhilpravinpise]

## 07:05 Phone ready (issue #84 device check done)
- [ ] Clips on the phone: `eurogames_bern_1.webm` (live) and `h13g_singles.mp4` (fallback). [@darved2305]
- [ ] Court lines, mini court, off-frame corner ring and hit timeline checked on the A55, screenshots posted on #84. If a feature does not draw, cut its beat from the script. [@darved2305]
- [ ] Wi-Fi on, notifications off, storage free, battery above 50%, screen-recording on.

## 07:10 Record (script: `demo/DEMO-SCRIPT.md`)
- [ ] One full take of the live run, about 3 minutes. [@darved2305 drives, @HetanshWaghela reads the script]
- [ ] If the live run stalls, record the analysed-earlier fallback and say so in the voice-over.
- [ ] Play the file back once, with sound: no notification pop-ups, no secrets on screen (API keys, tokens), nothing cut off.
- [ ] A second take as a spare if time allows; keep both.

## 07:25 Upload the video
- [ ] Upload the recording to the form's required host. Set the link to viewable without login. [@HetanshWaghela]
- [ ] Open the link in a private window to confirm it plays.
- [ ] Paste the link into `docs/submission/SUBMISSION.md` ("Demo video"), commit locally.

## 07:35 Links to check (open each the way a judge would)
- [ ] Repository https://github.com/darved2305/ggw_elevate is private: give the judges access or confirm how the form wants it shared. [@darved2305]
- [ ] Every PR and issue link in `README.md` opens: #6, #14, #15, #22, #29, #40, #49, #50, #54, #58, #69, #70, #72, #73, #74, #75, #76, #78 and issues #61, #67, #79, #80, #82, #83, #84.
- [ ] `docs/ops/cloudflare-budget.md` and `docs/plan/contracts.md` open from the README.
- [ ] Update the README status lines if anything merged since 06:00: issue #80 (calibration pull request), #79 and #82 (finalize and match read). Only claim what a PR or issue shows.
- [ ] No claim without a link; no em dashes; no internal shorthand in README, SUBMISSION or the script.

## 07:45 Submit
- [ ] Fill the form from `docs/submission/SUBMISSION.md` (pitch, problem, solution, technical novelty, proof links, team, limits). [@HetanshWaghela]
- [ ] Attach the demo video link and the repository link.
- [ ] Submit. Screenshot the confirmation page.
- [ ] Post the confirmation in the team chat before 08:00. Do not push more changes to `main` after submitting.
