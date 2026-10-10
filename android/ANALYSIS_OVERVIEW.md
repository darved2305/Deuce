# Analysis sync, window results and Match Overview (Phase 2) · integration note

Status: JVM-tested (121 unit tests in the slice build), read-only checks against the deployed
API. Not yet run on a phone with a live analysis; that needs the first real upload.

## Files
| Path (`app/src/main/java/com/elevate/app/`) | Role |
|---|---|
| `data/analysis/AnalysisClient.kt` | `HttpAnalysisClient(baseUrl, apiKey, tokenProvider)`: `POST /v1/analyses` (Idempotency-Key; 202 dispatched / 200 existing; 409 `upload_not_complete`; 502 `engine_unavailable`), `GET /v1/analyses/{id}`, events cursor (limit ≤ 500), contract-only artifact URL + `window_result` download (URL never logged), contract-only `match.v1` |
| `data/analysis/AnalysisSync.kt` | Drives the existing `EventLog`: polls the cursor while observed, pages until `has_more` false, refetches gaps from the last contiguous seq, restores the persisted cursor and catches up before presenting state as current (`isCatchingUp`), reconnects, downloads window results on `window.completed` |
| `data/analysis/CursorStore.kt` | `FileCursorStore(dir)`: atomic JSON cursor per analysis |
| `domain/analysis/AnalysisUiState.kt` | Exact contract states, coverage (merged `emit_range_us`), windows done/failed, stage times |
| `domain/analysis/WindowResultStore.kt` | Each window owns only its `emit_range_us`; re-delivery replaces; invalid rows rejected and counted; binary-search track |
| `playback/WindowTrailAdapter.kt` | Window ball rows → `TrailTrack` (engine provenance; observed vs inferred kept apart) |
| `ui/overview/*` | `AnalysingScreen`, `MatchOverviewScreen`, `OverviewViewModel`, components, all text in `AnalysingText.kt` |

## Entry points
- `AnalysingScreen(state: AnalysisUiState, onOpenResults, onRetry, onBack)`
- `MatchOverviewScreen(state: MatchOverviewState, onOpenRally: (rallyId, startMs, endMs) -> Unit, onBack)`
- `OverviewViewModel(client, analysisId, matchId?, title, cursorStore?, sourceDurationUs?, sourceWidth?, sourceHeight?, sourceFrameTimesUs?, fixture: Pair<String, TrailTrack>?, thumbnailLoader?, sync?)` + `OverviewViewModel.Factory {}`; `startObserving()` / `stopObserving()` (call from ON_START / ON_STOP), `retryNow()`, `engineTrail(clip)`
- `REFERENCE_RALLY_ID` is passed to `onOpenRally` for the hand-labelled reference replay.

## Wiring (lead)
After `upload/complete`: `startAnalysis(sessionId)` with a persisted Idempotency-Key → construct
`OverviewViewModel` with `FileCursorStore(File(filesDir, "analysis"))`, source size/duration from
`MediaProbe`, the hand-labelled fixture when the file's sha256 matches, `FrameThumbnail.load` on IO.
Rally replay: `controller.setTrack(...)` then `controller.seekTo(startMs)`; the fixture track for
`REFERENCE_RALLY_ID`, otherwise `vm.engineTrail(clip)?.observed`.

## Change requests (files outside this slice)
- `TrailOverlay`: draw `inferred` points as hollow rings with dashed joins, never solid-joined to
  observed points; `candidate` only behind a labelled toggle. Until then only `.observed` is passed.
- `EventLog`: nothing required (a `pendingCount` would help diagnostics).

## Live read-only checks (anonymous JWT, nothing created)
| Request | Response |
|---|---|
| `GET /v1/analyses/a_probe` | 404 `not_found` "analysis not found" |
| `GET /v1/analyses/a_probe/events?after_seq=0` | 404 "analysis not found" |
| same with `limit=501` | 422 `validation_failed` |
| `GET /v1/matches/m_probe`, `GET /v1/artifacts/art_probe` | 404 "route not found" |

## UNVERIFIED
Any 200 from the analysis routes (needs a real upload); the artifact response body (assumed
`{url, expires_at?}`, no schema in contracts §4); downloading a real `window_result.v1`; `match.v1`
as served; on-device screens and TalkBack.

## Person 3 still needs to deploy
1. `GET /v1/artifacts/{id}` (critical path for drawing engine output). 2. `GET /v1/matches/{id}`.
3. Optional Realtime `analysis_seq` hint (polling works without it).
