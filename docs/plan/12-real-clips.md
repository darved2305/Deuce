# 12 · Real clips: the core of the product

Players see their game in **real footage**, never cartoons. This file pins down where every clip
comes from and how it is cut, stored, synced, labelled and played. The UI concept screens use
drawings only because a mockup cannot embed video; in the app every visual sits on real video.

## 1. Rules

1. Every overlay (trail, landing marks, paths, court lines, contact areas, telestration) is drawn
   on **real frames**, keyed to the frame's presentation timestamp (Media3 frame effect).
2. Every comparison clip is a **real clip** of a real person, with provenance and role labels
   (`06` §3.2): `SIMILAR MOMENT`, `WORKED FOR YOU · n/N`, `COACH PICK`. A `SIMULATED` top-down
   animation appears only when no real clip exists, and is labelled.
3. We never generate or alter footage to show something that did not happen. Smoothed slow
   motion is display-only and labelled.
4. Every clip remembers match, timestamps, owner, rights and attribution.

## 2. Sources

| Source | Where the pixels live | How we play it | Provenance chip |
|---|---|---|---|
| This match | The original in the gallery | ExoPlayer on the original from `start_ms` (no copy) | `THIS RALLY` |
| Your past matches | Gallery original if present, else the server-cut clip in R2 | Local first, else signed URL | `YOUR MATCH · date` |
| Another player (consented) | Their server-cut clip in R2 | Signed URL after a share-grant check | `SHARED BY name` |
| Team reference library | R2, seeded by Person 4 | Signed URL | `TEAM REFERENCE` (+ attribution) |
| Public CC material | R2, only after per-video rights check | Signed URL | `REFERENCE · author · licence` |
| Simulated | none | Top-down court animation | `SIMULATED` |

## 3. Cutting clips (server, Person 1's finalizer)

- For every moment (one per shot) and every rally: FFmpeg cut from the uploaded original,
  re-encoding only the short clip (H.264 ≤1080p, CRF ~20, `faststart`, keyframe at start, audio
  kept) so it seeks instantly. Shot window: `contact_ms − 1500` to `contact_ms + 1500`
  (configurable per sport); rally clips cover the rally.
- Store `contact_ms` in the clip record (enables sync). Poster frame = contact frame (JPEG) for
  thumbnails, the widget and notifications.
- Overlays are **not** burned into stored clips (they stay correct after corrections). Only
  exported share videos burn overlays in (Media3 Transformer on the phone, same effect code).

## 4. Choosing the comparison clip (Person 3)

From the similar-moment search (`06` §3.1): candidates whose `clip.rights` allow this viewer;
prefer own history, then shared, then team reference, then public CC; role label from the
evidence gate. Nothing suitable → `SIMULATED` animation, or "No similar moment yet".

Demo seeding (Person 4 supplies footage, rights and seed JSON; Person 3's `seed_demo_match` loads it): team-recorded drills (e.g. deep clear vs short lift from the back
corner; cross-court vs straight from the forehand corner) run through the **real** engine and
tagged, so the demo comparisons are genuine footage with genuine records.

## 5. Syncing two clips (Person 2)

Both clips carry `contact_ms`; the compare player aligns both contacts to one marker and drives
both with one scrubber, mapping by time (not frame index). Different camera angles: the shared
court map below the clips is the common ground (ghost path drawn there). A55 fallback: one clip
plays, the other shows the frame at the same relative time.

## 6. Exported highlight

Vertical 1080×1920 via Media3 Transformer with the same overlay effect, a score bug and one
caption; attribution text added automatically for reference or shared clips; original untouched.

## 7. Rights and consent

Own clips private by default; another player's clips appear in my comparisons only with their
`share:compare` grant; reference clips only from team or partner material, or per-video-checked
CC licences with attribution stored and shown; broadcast footage never; deleting a match deletes
its clips everywhere, including other users' comparison caches.
