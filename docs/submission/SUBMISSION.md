# Elevate 1.0 - submission text (PS-02)

## One-line pitch
Film a badminton match on any phone, and a cloud engine with no trained models draws the shuttle's
path, hits and court on your own video, showing the evidence for every number.

## Problem
Pro venues have multi-camera tracking; clubs and academies have a phone. Match records are
missing or scattered, scores are inconsistent, and trajectory data does not exist. How much of a
professional tracking system can one normal camera rebuild, with no training?

## Solution
An Android app plays the player's own video at once and uploads it to private cloud storage. A GPU
engine analyses it in 6-second chunks and reports each chunk as it finishes, so results appear on the
real footage while the rest is still processing: shuttle trail, hit candidates, rally candidates,
court lines and a top-down court map. The API serves the same match record to the app and to
developers. Uncertain output is withheld and labelled, never invented.

## What is technically new
No model is trained or fine-tuned.
- **Shuttle tracking:** a published shuttle-detection network (TrackNetV3), used as released, plus a
  motion-tracking layer (Kalman filters, a standard motion-prediction method, with gated matching) that removes false detections
  such as the back-wall spot. Precision rose from 78.1% to 100% at 79.7% recall on 196 hand-labelled
  frames.
- **Hits from sound and motion:** audio onsets (sudden jumps in sound energy) fused with direction changes in the
  tracked shuttle path.
- **Court from 4 taps:** a projective mapping from the picture to the floor plan, with a measured
  quality grade; corners may sit outside the frame, so the whole court is recovered by geometry.
- **Rules engine:** BWF badminton scoring, law-cited, with 45 test vectors passed identically by the
  server and the Kotlin app.

## Proof
Repository: https://github.com/darved2305/ggw_elevate (README lists each claim with its link).
- Phone to cloud to GPUs to streamed results: PRs #29, #50, #54, #74
- Shuttle tracking numbers: PR #49. Hits: PRs #58, #76
- 4-tap court calibration on real taps, grade B: PR #87 (real taps on issue #61); the app and the engine agree to within 1 cm (PR #88)
- Scoring rules and 45 vectors: PRs #40, #69, #70
- Court lines, mini court, hit timeline, off-frame corners: PR #78
- Cost guard (cloud use capped at 20% of the free tier, in code): docs/ops/cloudflare-budget.md
- Demo video: (add link after the 07:10 recording)

## Team
- Android app: @darved2305
- Vision engine on Modal: @VrajVed
- API, database and storage: @nikhilpravinpise
- Data contracts, rules, test footage, CI and demo: @HetanshWaghela

## Honest limits
- The live run does not yet end with a full match record, so no score, landings or line calls
  appear from it (issues #79, #82). Court calibration is not yet in the live result (#80).
- Tracking numbers are on the clip we tuned on; the untouched test segment is not scored yet.
- Hit and rally markers are candidates checked by eye; no hand-labelled hits exist yet.
- Court calibration on real taps is grade B (0.19 m uncertainty), not the best grade.
- Court lines and the mini court are merged and unit-tested; on-phone verification is in progress (#84).
- A cold engine takes about 2 minutes to start, and a full match takes about 1.3 times its length.
- Badminton only; no accuracy claim for other sports or for handheld footage.
