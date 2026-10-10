# Upload client (Person 2, Phase 2 · G2)

Phone → Elevate API (`POST /v1/sessions`) → presigned R2 multipart PUTs → `upload/complete`, to
`docs/plan/contracts.md` §1–§2 (**contracts 1.4.0**, D-261011-HW6) and the R2 budget rules in
`docs/ops/cloudflare-budget.md` (G1 300 MiB, G4 URL allowance, G6 short-lived URLs, G11 ≤ 4
attempts per part). No OkHttp/Ktor/Room: `HttpURLConnection` + kotlinx.serialization + atomic
JSON files.

## Files

| Path (under `app/src/main/java/com/elevate/app/`) | What |
|---|---|
| `data/auth/SupabaseAuth.kt` | Anonymous sign-in (`POST {SUPABASE_URL}/auth/v1/signup` `{}` + `apikey`), refresh (`/auth/v1/token?grant_type=refresh_token`) 60 s before expiry, single-flight (one `Mutex`). |
| `data/auth/TokenStore.kt` | `FileTokenStore` (`filesDir/auth/session.json`, atomic write), `InMemoryTokenStore`. |
| `data/session/HttpTransport.kt` | The one network seam (`HttpTransport`), `UrlConnectionTransport` (fixed-length streaming, no redirects), `redactUrl`. |
| `data/session/ElevateHttp.kt` | `Authorization: Bearer` + `apikey` on every call, `Idempotency-Key`, `error.envelope.v1` → `ApiException(status, code, message, details, retryAfterS)`; 401 → refresh once → retry once; 5xx / transient 429 / transport errors → ≤ 3 attempts with backoff. |
| `data/session/SessionModels.kt` | `session.create.request.v1` (from caller pieces), `session.create.response.v1`, `upload.parts.*`, `upload.complete.*`, `upload.abort.response.v1`, `session.get.response.v1`. |
| `data/session/SessionClient.kt` | `SessionApi`: create, get, part URLs (≤ 50 per call), complete, abort. |
| `data/upload/PartPlan.kt` | Part slicing (1-based, short last part), `FilePartSource`, bounded stream, 64 KiB copy buffer. |
| `data/upload/MultipartUploader.kt` | ≤ 2 parts in flight, ETag captured verbatim, retries (G11), 403/expired URL → re-request, state saved after every accepted part, ordered idempotent complete. |
| `data/upload/UploadCoordinator.kt` | One upload: `prepare` → `resume` → `cancel`/`dismiss`; `progress: StateFlow<UploadProgress>`. |
| `data/upload/UploadState.kt` / `AtomicFile.kt` | Persisted upload (`filesDir/uploads/current.json`), write-temp-fsync-rename. |
| `data/upload/UploadProgress.kt` | Progress states, `RateMeter` (10 s sliding window), `RetryPolicy` (4 attempts, exp backoff, equal jitter). |
| `data/upload/UriSource.kt` | `UriPartSource` (content Uri byte ranges via `openFileDescriptor` + `FileChannel.position`), `UriAccess.prepare` (persist grant, else StatFs-checked, sha256-verified staging copy). |
| `data/upload/UploadRuntime.kt` | Process-wide graph from BuildConfig (UI and job share one coordinator). |
| `data/upload/UploadScheduler.kt` | `start` / `stop` the background run (API 34+ job, else WorkManager). |
| `data/upload/UploadJobService.kt` | API 34+ user-initiated data transfer job. |
| `data/upload/UploadWorker.kt` | API 26–33 fallback: WorkManager 2.11.2 worker in a `dataSync` foreground service. |
| `data/upload/UploadNotifications.kt` | Channel `uploads`, real-bytes notification. |
| `ui/upload/UploadProgressCard.kt`, `ui/upload/UploadText.kt` | The card and the shared wording. |

## Integration steps for the lead

Config (machine-local, never in git): `android/local.properties`
`elevate.supabaseUrl`, `elevate.supabasePublishableKey`, `elevate.apiBaseUrl`
→ `BuildConfig.SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `API_BASE_URL`. Check
`UploadRuntime.isConfigured` before showing an upload button.

1. **Sign in** (optional; any API call signs in on demand):
   `UploadRuntime.get(context).auth.accessToken()` (suspend). `auth.currentUser()` gives
   `userId` / `isAnonymous` (the app must say history is tied to this phone until an identity is linked).
2. **Make the source readable after process death** (on the Analyse tap, off the main thread):
   `UriAccess.prepare(context, uri, facts.bytes, sha256)` → `SourceAccess.Persisted(uri)` or
   `SourceAccess.Staged(file)` (throws `InsufficientStorageException`, or `IOException` if the file
   changed since its fingerprint).
3. **Build the request from pieces** (nothing is invented here; missing facts throw):
   ```kotlin
   val source = SessionSource.from(facts /* MediaProbe */, sha256 /* VideoInspector.fingerprint */, device = Build.MODEL)
   val request = SessionCreateRequest(
       sport = "badminton", format = "singles", ruleset = Ruleset("bwf_3x21", "2025-04"), mode = "match",
       source = source,
       calibrationInput = calibrationJson ?: JsonNull,   // court/floor taps object or JsonNull
       whoIsMe = whoIsMeJson,                            // {image_xy:[x,y], t_ms}: required
       freePlaySeed = seedJson ?: JsonNull,              // {t_ms, image_xy} or JsonNull
       rallyCandidates = ralliesJson,                    // JsonArray, may be empty
       uploadMode = "multipart",
   )
   ```
   `SessionSource.from` refuses a clip without `audio_rate_hz` unless the lead passes
   `audioRateHzWithoutAudio` (open contract question 4, `PHASE2_DEPENDENCIES.md`).
4. **Start**: `val rt = UploadRuntime.get(context)`
   `rt.coordinator.prepare(request, sourceUri = uri.toString(), stagedPath = (access as? SourceAccess.Staged)?.file?.path)`
   then `UploadScheduler.start(context, estimatedUploadBytes = source.bytes)` **while the app is
   visible** (returns false if the system refused). On API 33+ request `POST_NOTIFICATIONS` first
   (without it the upload still runs; API 34+ shows it in Task Manager).
5. **Observe**: `rt.coordinator.progress` (`StateFlow<UploadProgress>`): `collectAsStateWithLifecycle()`.
6. **Show**: `UploadProgressCard(progress, onRetry = { UploadScheduler.start(ctx, bytes) }, onCancel = { scope.launch { UploadScheduler.stop(ctx); rt.coordinator.cancel() } })`.
7. **Resume after process death**: on app start, if `rt.coordinator.current()?.phase` is not
   `Completed`/`Aborted`, show the card (it starts as Failed/Paused/"Waiting to resume") and offer
   Retry; API 34+ jobs and WorkManager also re-run on their own after an offline stop.
8. **Done**: `UploadProgress.Completed(serverBytes)` / `current()!!.sessionId`, `matchId` →
   `POST /v1/analyses` (not in this slice). `rt.coordinator.dismiss()` clears the finished upload.

Manual job run (device): `adb shell cmd jobscheduler run -f -n elevate.upload com.elevate.app 4201`
(namespace flag UNVERIFIED on our phones).

## Behaviour

- **Size:** 1 byte .. 300 MiB (G1), refused in `prepare` before any network call; the server's
  part size (8 MiB) is re-checked against the file.
- **Parts:** streamed from the Uri (`FileChannel.position`, 64 KiB buffer, `Content-Length` = part
  length, fixed-length streaming mode); ≤ `min(max_concurrency, 2)` in flight; the `ETag` header is
  stored exactly as returned (quotes kept).
- **Retries (G11):** each part ≤ 4 attempts. Transport error / 5xx / 429 → backoff 1 s·2^k with
  equal jitter (0.5–1 s, 1–2 s, 2–4 s). 403 → that URL is dropped and a fresh one requested at once
  (counts as an attempt). A URL is reused until 60 s before its `expires_at` (G4 allowance), then
  re-requested. After the 4th failure the run stops: `Failed(part_failed, part = k)` or
  `PausedOffline`; the user retries; parts already accepted are kept.
- **Persistence:** state saved atomically after create and after every accepted part; a new
  process reads it and requests URLs only for missing parts. A create interrupted by death is
  retried with the same `Idempotency-Key` (the server replays the session).
- **Complete:** all parts in order; a repeated complete returns the same `{status, bytes}`;
  `upload_parts_invalid` with `details.missing` re-sends those parts once; `invalid_transition`
  → reads `GET /v1/sessions/{id}` and accepts `completed`.
- **Cancel:** stops the run, `upload/abort` (409/404 tolerated), deletes state and staging copy.
- **Progress:** `acceptedBytes` (parts with an ETag) and `inFlightBytes` (bytes actually written
  to the socket for unfinished parts) are separate; rate = sliding 10 s window of written bytes,
  null until 1 s of samples; ETA only from that rate. No timers, no fake bars.
- **Secrets:** tokens never in `toString`/messages; presigned URLs only appear redacted
  (`redactUrl`); R2 PUTs carry no auth headers.

## Background execution

- **API 34+:** `UploadJobService`, a user-initiated data transfer job (`setUserInitiated(true)`,
  `NETWORK_TYPE_ANY`, `setEstimatedNetworkBytes(download, upload)`, namespace `elevate.upload`),
  `setNotification(..., JOB_END_NOTIFICATION_POLICY_DETACH)` in `onStartJob`, updated ≤ 1/s with
  `updateTransferredNetworkBytes(params, 0, sent)`. `onStopJob` → cancel + reschedule; finish
  reschedules only when offline. Signatures checked with `javap` against `android-37/android.jar`
  and AOSP `JobService.java`/`JobInfo.java` (parameter order download, upload); flow per
  developer.android.com "User-initiated data transfer".
- **API 26–33:** no UIDT there. `UploadWorker` (WorkManager **2.11.2**, the only new dependency)
  runs as a long-running worker in a `dataSync` foreground service (manifest merges
  `foregroundServiceType="dataSync"` into WorkManager's `SystemForegroundService`). Chosen over a
  hand-written dataSync service because WorkManager already persists the request across process
  death/reboot, applies the network constraint and backoff, and handles `setForeground`; this is
  the fallback the Android UIDT page recommends.
- **Room deviation:** `android/AGENTS.md` says "persist uploadId/parts/ETags in Room". This slice
  keeps one upload in one atomically written JSON file instead: the state is a single small record
  (≤ 38 ETags), read whole on resume, and Room would add KSP + a schema for one row. Revisit if
  several concurrent uploads are ever needed (record a decision then).

## Gradle and manifest changes

- `libs.versions.toml`: `work = "2.11.2"`, `androidx-work-runtime`.
- `app/build.gradle.kts`: `implementation(libs.androidx.work.runtime)`; BuildConfig
  `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` from `local.properties` (beside the existing `API_BASE_URL`).
- Manifest: `ACCESS_NETWORK_STATE`, `RUN_USER_INITIATED_JOBS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; `UploadJobService`
  (`BIND_JOB_SERVICE`, not exported); WorkManager `SystemForegroundService` typed `dataSync`.

## Contract vs deployed API (2026-10-11)

- `POST /v1/sessions/{id}/upload/parts`: contracts 1.4.0 say `{parts: [{n, url, expires_at}]}`;
  the deployed API (its `/v1/openapi.json`, and the live run) still answers the pre-1.4.0 **bare
  array of URL strings** ("presigned PUT URLs (600 s), in the order requested"). The client reads
  1.4.0 and, through a labelled legacy shim (`SessionClient.parsePartUrls`), the bare array
  (n = request order, `expires_at` = issue time + 600 s). **Ask Person 3 to deploy the 1.4.0 shape**;
  then delete the shim.
- `GET /v1/sessions/{id}`, `upload/complete`, `upload/abort`: the live answers matched the 1.4.0
  schemas (read strictly).
- Error codes the deployed code uses that are not in `knownErrorCodes`: `invalid_transition` (409),
  `upload_parts_invalid` (409), `invalid_part_number` (400), `calibration_template_mismatch` (422)
  (from `p3/sessions-upload` source). The client handles them by status; for Person 4 to list.

## Proof

### Unit tests (JVM, `./gradlew testDebugUnitTest`)
56 tests in 6 classes, all against fakes, plus 1 gated live check:
`SupabaseAuthTest` (6), `ElevateHttpTest` (12), `SessionContractTest` (10, request bodies validated
against `packages/contracts/schemas/*.schema.json`, response readers field-checked against the 1.4.0
schemas), `UrlConnectionTransportTest` (3, real local socket), `PartPlanTest` (6),
`UploadCoordinatorTest` (19). `ContractFakeServer` is a **test fake** mirroring Person 3's route
behaviour (idempotency replay/conflict, 413/422, G4, invalid_transition, upload_parts_invalid with
`missing`, idempotent complete/abort), GoTrue and R2 PUTs (MD5 ETags, URL expiry → 403).

### Live run (deployed API + real R2), exactly one upload
`LiveUploadCheck` (`ELEVATE_LIVE=1`, skipped otherwise), run once on 2026-10-11 local time (21:19 UTC on 2026-10-10) from the dev PC
(JVM, production classes: `SupabaseAuth`, `ElevateHttp`, `SessionClient`, `UploadCoordinator`,
`UrlConnectionTransport`):

- Clip: `eurogames_bern_1.webm` (Wikimedia Commons, CC BY-SA 4.0, `proof/video-manifest.md`),
  7,717,311 bytes, sha256 `3669343e1837befefe9f77ae3b6a740767e4b6e59054d54b9cbbac1042d786ed`
  (matches the manifest). Source facts: VP8 848×464, rot 0, 30 fps stated, 767 frames,
  33000/33000/67000 µs, 1 gap (ffprobe, manifest); duration 25,603 ms and Vorbis 44,100 Hz read
  from the file's Matroska header (no ffprobe on this PC). `device = "jvm-live-check"`,
  `mode = "practice"`, `calibration_input = null`, `rally_candidates = []`. `who_is_me =
  {image_xy: [424, 232], t_ms: 0}` is the frame centre, **not a user tap** (the contract requires
  one); this session proves the upload path only and must not be analysed as a real match.
- Anonymous user (`is_anonymous = true`).
- `session_id = s_603875b963da4a358513c5d651f05b97`, `match_id = m_4828e30c2ba144c794a5b8623288b772`,
  `part_size = 8388608`, 1 part, `max_concurrency = 2`.
- Upload: 1 part PUT to R2, 1 ETag, complete → `{status: "completed", bytes: 7717311}`;
  8.3 s end to end; progress states Preparing → Uploading (56 samples, peak measured 3.45 MB/s) →
  Completing → Completed.
- `GET /v1/sessions/s_603875b963da4a358513c5d651f05b97`: `upload.status = completed`,
  `bytes = 7717311`, `part_count = 1`, `completed_at = 2026-10-10T21:19:51.113+00:00`,
  `source.sha256` as sent.
- Part URLs came back in the legacy bare-array shape (see above).

### Verified vs UNVERIFIED
- **Verified (fakes):** slicing, size limits, ETag capture, backoff/jitter, 4-attempt cap, 403
  re-request, URL expiry, 401 → refresh → retry, 429/500/503, offline pause + resume, resume after
  simulated process death (new instance on the same files), idempotent create replay, duplicate
  complete, missing-parts repair, abort/cancel (also mid-flight), ≤ 2 concurrent PUTs, progress =
  accepted + written bytes only, error-envelope parsing, request/response JSON vs schemas.
- **Verified (live, once):** anonymous sign-in, create, part URL, real R2 PUT with ETag, complete,
  GET session, all against the deployed API.
- **UNVERIFIED:** anything on a phone. The API 34+ job (scheduling while visible, notification,
  `updateTransferredNetworkBytes`, `onStopJob` rescheduling), the API 26–33 WorkManager fallback
  and its foreground start on Android 12–13, real process death mid-upload on a device,
  `takePersistableUriPermission` on Photo Picker Uris (else the staging copy), `FileChannel`
  positioning on picker file descriptors, `UploadProgressCard` on screen (no screenshot yet), the
  `-n` namespace flag of `cmd jobscheduler`, multi-part (> 8 MiB) uploads against the live API
  (only a 1-part upload ran live; multi-part ordering is covered by fakes only).
