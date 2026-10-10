# Phase 2 · what Android needs from other lanes (Person 2)

Checked 2026-10-11 against the **deployed** API (`{SUPABASE_URL}/functions/v1/api`) with a real
anonymous JWT (`POST /auth/v1/signup`, `is_anonymous: true`) and the project's publishable key.
Without a JWT every `/v1/*` path answers 401, including paths that do not exist, so an
unauthenticated probe proves nothing. With a JWT, a missing route answers
`404 {"error":{"code":"not_found","message":"route not found"}}`.

## Contract readiness

| Operation | Route (contracts §) | Request / response schema | Auth | Deployed | Code exists | Owner |
|---|---|---|---|---|---|---|
| Liveness | `GET /v1/health` (§1) | `{status:"ok"}` | none | **yes** (200) | main | P3 |
| Create session + multipart upload | `POST /v1/sessions` + `Idempotency-Key` (§2) | `session.create.request.v1` → `session.create.response.v1` | JWT + apikey | **no** (404) | branch `p3/sessions-upload` (unmerged) | P3 |
| Presigned part URLs | `POST /v1/sessions/{id}/upload/parts` (§2) | `upload.parts.request.v1` → `upload.parts.response.v1` (array of URLs, 600 s) | JWT | **no** | `p3/sessions-upload` | P3 |
| Complete upload | `POST /v1/sessions/{id}/upload/complete` (§2) | `upload.complete.request.v1` → `{status:"completed", bytes}` (*proposed, not in contracts*) | JWT | **no** | `p3/sessions-upload` | P3 (+P4 to add the response) |
| Abort upload | `POST /v1/sessions/{id}/upload/abort` (§2) | → `{status:"aborted"}` (*proposed*) | JWT | **no** | `p3/sessions-upload` | P3 (+P4) |
| Read session/upload | `GET /v1/sessions/{id}` | *proposed, not in contracts* | JWT | **no** | `p3/sessions-upload` | P3 (+P4) |
| Start analysis | `POST /v1/analyses` + `Idempotency-Key` (§3) | `analysis.create.request.v1` → `analysis.create.response.v1` (202 dispatched / 200 existing) | JWT | **no** | `p3/analyses-routes` (unmerged) | P3 |
| Read analysis | `GET /v1/analyses/{id}` | *proposed, not in contracts* | JWT | **no** | `p3/analyses-routes` | P3 (+P4) |
| Event cursor | `GET /v1/analyses/{id}/events?after_seq&limit` (§3) | `analysis.events.page.v1` | JWT | **no** | `p3/analyses-routes` | P3 |
| Realtime hint | private channel `analysis:{id}`, event `analysis_seq` (§3) | `realtime.hint.v1` | JWT (RLS on `realtime.messages`) | unverified | unknown | P3 |
| Window result download | `GET /v1/artifacts/{id}` → signed URL (§4) | URL, then `window_result.v1` | JWT | **no** | **none found on any branch** | P3 |
| Match record | `GET /v1/matches/{id}` (§7) | `match.v1` | JWT | **no** | **none found on any branch** | P3 |
| Capabilities | `GET /v1/capabilities` (§6) | model descriptors | — | **no** | none found | P3 |
| Modal submit | `POST {MODAL_SUBMIT_URL}` (§3, P3 → P1) | `modal.submit.request.v1` | bearer | unverified | branch `p1/submit-endpoint` (unmerged) | P1 |
| Windowed inference posting `window.completed` | `/internal/analyses/{id}/events` + artifacts | `internal.event.request.v1` | internal token | `/internal/*` answers 401 (exists on the deployed build) | P1 runs TrackNet on a clip range on Modal (#24); not yet wired to `/internal` | P1 |

## What each gate waits for

- **G2 Upload (real R2):** P3 merges and deploys `p3/sessions-upload`. Android builds the client
  against that code and the contract schemas now and tests it against a local contract server;
  the gate stays **BLOCKED** until a real upload completes against the deployed API.
- **G3 Analysis:** P3 deploys `p3/analyses-routes` (+ dispatch); P1 deploys `submit`. Until then
  `POST /v1/analyses` cannot run. Once routes exist but Modal does not, the real answer is
  `502 engine_unavailable` plus an `analysis.failed` event, and the app shows exactly that.
- **G4 Result:** P1 posts `window.completed` with a `window_result` artifact; P3 adds
  `GET /v1/artifacts/{id}` (no code for it exists yet). **Ask P3: artifact route is the critical path.**
- **G5 Replay on live data:** G4 plus the existing Phase 1 overlay. Meanwhile, P1's real TrackNet
  output on `h13g_singles` (#24) is genuine engine output; if P4 records it as an engine-output
  fixture (`engine_version` = the engine's), the app can draw real model detections before the
  live path exists, labelled as recorded engine output, never as live.

## Contract questions raised by Android (for P4)

1. `upload/complete`, `upload/abort`, `GET /v1/sessions/{id}`, `GET /v1/analyses/{id}` responses
   are only "proposed" in P3's code. Android reads them leniently until P4 adds schemas.
2. `source.pts.gaps` and `source.pts.vfr` have no definition in §2. Android computes:
   gap = interval > 1.5 × the median positive interval; vfr = > 5 % of non-gap intervals off the
   median by > 10 %. **Gaps** equal ffprobe's count on six real clips (`MediaProbeCorpusTest`).
   **VFR is unverified on real footage**: ffprobe has no VFR output and no slow-motion clip exists
   yet; the rule is only unit-tested. These definitions interpret a contract field, so before the
   app sends them they need P4's sign-off and a `DECISIONS.md` entry (rules 5/6).
3. `fps_nominal` (required number): Android sends the container's rate, else the rate from the
   mean measured spacing. It can still be unknown (no frames); the request is not sent then.
4. `source.audio_rate_hz` is a required non-null integer, but a clip without audio has no rate.
   Send `0`, or make it nullable?
5. `source.pts` has `additionalProperties: false`; Android's frame count stays local.
