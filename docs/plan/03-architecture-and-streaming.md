# 03 · Architecture and the streaming pipeline

Decided in debates D1 and D2, checked by reviews R1–R3 (`11-debates.md`). Exact shapes live in
`contracts.md`.

## 1. Shape of the system

```text
 PHONE · native Android (Kotlin, Compose, Media3)          CLOUD (free tiers + Modal credit)
 ┌────────────────────────────────────────────┐   ┌────────────────────────────────────────────┐
 │ Photo Picker → local playback starts       │   │ API: Hono on Supabase Edge Function `api`  │
 │ Preflight: real PTS, codec, rotation       │──▶│  {SUPABASE_URL}/functions/v1/api/v1/…      │
 │ Audio onsets → rally candidates (local)    │   │  sessions, R2 presign, analyses, cursor,   │
 │ Upload: R2 multipart, resumable (UIDT job) │   │  matches, reviews, comparisons, /internal  │
 │   (later: remuxed segments behind a flag)  │──▶│ R2 (private): originals, window results,   │
 │ Realtime hint → cursor fetch by seq        │◀──│  tracks, clips, posters                    │
 │ Overlays via Media3 frame effect (PTS)     │   │ Supabase: Auth, Postgres (Mumbai), RLS,    │
 │ Room: history cache, upload state          │   │  Realtime Broadcast `analysis_seq`         │
 │ Glance widget, notifications               │   │ Modal: `submit` → analyze.spawn() on a T4  │
 └────────────────────────────────────────────┘   │  ordered windows → R2 + /internal API      │
                                                  │ Modal CPU: insights job (Person 3)         │
                                                  └────────────────────────────────────────────┘
```

Rules: video bytes never pass through the API (Edge Functions have tight CPU limits); the phone
plays its own original; Postgres holds structured records, R2 holds bytes and large arrays; the
Modal job never writes Postgres directly, it reports through `/internal` and Person 3's API is the
only writer of events and Broadcasts.

## 2. What the user feels, step by step

| Step | Phone | Cloud | User sees |
|---|---|---|---|
| Pick | Photo Picker returns the video | — | The video playing |
| Setup | Preflight + audio onsets locally; four named corner taps (template matches singles/doubles) or Free play + one ball tap; sport; who-is-me | `POST /v1/sessions` | Rally candidates on the timeline, dotted, "from sound" |
| Upload | R2 multipart, 8 MiB parts, 2 concurrent; upload ID, parts and ETags in Room; user-initiated data transfer job with a notification; resume after interruption | parts presigned; `upload/complete` idempotent | Real bytes sent, measured rate, ETA from actual throughput |
| Analyse | Subscribes to `analysis:{id}`; on each `analysis_seq` hint fetches the cursor | `POST /v1/analyses` (idempotent) → Modal `submit` → ordered windows → `window.completed` events | Candidate bars turn solid as windows complete; the trail appears on the real video for finished windows |
| Finish | Notification with the hero frame | Person 1 `finalize` → Person 3 persists, runs insights, appends `analysis.completed` | Match overview |

Promise in the UI: **real stages and bytes, never a fake percentage**. Upload and processing
times are measured on the team's phones and network during Phase 2 and shown as measured.
Reference uplinks for Mumbai: TRAI route averages ~26–41 Mbps on mixed 4G/5G; Opensignal user
averages ~9–11 Mbps.

**Chunked real-time analysis (core, Phase 3; D-261011-HW3).** Analysis starts on the first chunk,
not after the whole upload:
1. **Phone (Person 2):** remux the original into ~6 s keyframe-aligned segments with MediaExtractor
   + MediaMuxer (stream copy, no re-encode). Each segment is a standalone MP4 that keeps its
   `pts_start_us`/`pts_end_us` on the original timeline. Upload in order, one presigned PUT each,
   2 in flight. Measure keyframe spacing on real S23 FE and A55 files; it sets the cut points.
2. **API (Person 3):** register each segment (`SegmentManifest`, contracts §2) and hand out its
   PUT URL (`seg/{session_id}/{seq}.mp4`); when segment 0 completes, create and dispatch the
   analysis; the last segment carries `final: true`. Proposed routes: `POST
   /v1/sessions/{id}/segments`, `POST /v1/sessions/{id}/segments/{seq}/complete`, and for the
   engine `GET /internal/analyses/{id}/segments?after_seq=`. Final shapes land in the contracts
   amendment.
3. **Engine (Person 1):** process segment k as soon as its manifest arrives (never list R2),
   borrow the tail of segment k-1 for window overlap and TrackNetV3's 8-frame context, keep the
   background median rolling, and post `window.completed` exactly as today, on the original
   timeline. Wait for the next segment with a timeout; finish on `final`.
4. **App:** the trail appears on finished segments while later ones are still uploading.

The whole-file multipart upload stays as the fallback for files the segmenter cannot cut cleanly
(moov placement, HEVC, rotation matrix, VFR, audio).

## 3. Phone components (Person 2)

| Component | Job | APIs |
|---|---|---|
| Import | Photo Picker (videos), persistable URI permission | `PickVisualMedia` |
| Preflight | Real PTS distribution, VFR, rotation, codec, audio presence | `MediaExtractor` |
| Audio onsets | Audio → PCM → spectral-flux onsets → rally candidates (sport gap rule), shown as candidates only | `MediaExtractor`, `MediaCodec` |
| Uploader | R2 multipart via presigned part URLs; idempotent; resume; UIDT job (API 34+) with notification; WorkManager 2.11.2 for deferred retry | JobScheduler, WorkManager |
| Player + overlays | ExoPlayer on the original; trail, marks, court lines and contact areas drawn by a Media3 frame effect keyed to presentation time; Compose for controls and cards | Media3 1.11.1 `setVideoEffects`, `OverlayEffect` |
| Export | Vertical highlight with the same overlay effect burned in | Media3 Transformer |
| Local store | Match cache, upload state, history for offline | Room 2.8.5 |
| Widget, notifications | Today widget, ready notification | Glance 1.2.0 |
| Realtime | `analysis:{id}` private channel, `analysis_seq` event | supabase-kt 3.8.0 |

## 4. Cloud components

| Component | Owner | Choice |
|---|---|---|
| API | Person 3 | Hono 4.13.13 + `@hono/zod-openapi` 1.6.3 on Supabase Edge Function `api`; OpenAPI 3.0.4 at `/v1/openapi.json` |
| Auth | Person 3 | Supabase Auth: anonymous first, identity linking before cross-install history; developer `X-API-Key`; match-scoped embed tokens; `X-Internal-Token` for Modal |
| Database | Person 3 | Supabase Postgres `ap-south-1`; RLS on every table (`owner_id = auth.uid()` or a share grant) |
| Events | Person 3 | `analysis_events` (seq assigned by `append_event` in one transaction) + Broadcast `analysis_seq` on private channel `analysis:{id}` with RLS on `realtime.messages` |
| Storage | Person 3 signs; Person 1 reads/writes with scoped secrets | Cloudflare R2 private bucket (`apac` hint), deterministic keys (`contracts.md` §4), signed with `aws4fetch` 1.0.20 |
| Vision job | Person 1 | Modal SDK 1.6.1; `submit` = `@modal.fastapi_endpoint(method="POST")` with bearer auth; `analyze.spawn()`; T4; broad `ap` region |
| Insights | Person 3 | Python job on Modal CPU, triggered by `finalize` |
| Captions (optional) | Person 3 | Workers AI `@cf/openai/gpt-oss-20b`, server-side, deterministic fallback |

## 5. The analysis lifecycle

States: `awaiting_upload → uploaded → queued → processing → partial → finalizing → completed |
failed`. Events: `analysis.queued`, `analysis.started`, `window.completed`, `window.failed`,
`analysis.partial`, `analysis.finalizing`, `analysis.completed`, `analysis.failed`, and the
domain event `point.confirmed`.

1. `POST /v1/analyses` creates the run (unique `(session_id, idempotency_key)`), stores the Modal
   call ID, and calls `submit`; a retry returns the same run and never spawns a second job.
2. The Modal job reads the original, processes ~6 s windows with 0.5–1 s overlap in order
   (`decode_window_us` includes the overlap; `emit_range_us` is the part credited to the window),
   carries tracking state across windows, writes artifacts to deterministic R2 keys and posts
   events through `/internal`.
3. `finalize` sends the vision result (rallies, shots, events, review estimates, metrics, clips).
4. Person 3 persists it, runs insights (hero insight, similar moments, comparison candidates),
   then appends exactly one `analysis.completed`.

Clients order and dedupe by `seq`; `after_seq` is exclusive; on a gap they refetch from the last
contiguous `seq`; polling runs while the analysis screen is visible.

## 6. Offline

Works offline: history (Room cache), local playback with cached overlays, score keeping and
corrections (queued), recording, uploads queued to resume. Needs network: new analysis and
optional captions.

## 7. Free-tier operations

- Supabase Free: 500 MB database, 1 GB storage (videos never stored there), 5 GB egress, 500k
  Edge Function invocations, 2M Realtime messages, 200 peak connections; projects pause when idle,
  so keep it active with a scheduled ping around the event.
- R2: 10 GB-month, 1M Class A and 10M Class B operations, free egress. Per-upload size cap
  (default 2 GB). Default retention: the server copy of an original is deleted after analysis
  unless the user keeps it (the original stays in their gallery); clips and posters are kept with
  consent.
- Modal Starter: $30 monthly credit, 3 seats, card required for GPU. The credit is not a spending
  cap: Person 3's spend guard stops GPU submissions before the agreed ceiling; one warm container
  only during demo windows, otherwise scale to zero.

## 8. Security and privacy

RLS everywhere; private R2 objects served only through short-lived signed URLs after an ownership
or share-grant check; consent per participant before any clip leaves its owner; `DELETE
/v1/matches/{id}` cascades through originals, artifacts, clips, posters, summaries and caches; the
LLM never receives video, faces, names or emails.
