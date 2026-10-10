# Timeline from sound · rally candidates (docs/plan/05 §3.2)

Local, on the phone, no network: decode the clip's audio, find sharp sound onsets, group them into
**rally candidates** (`state: "candidate"`). Candidates are hints for navigation, labelled
"from sound", drawn dotted. They are never confirmed rallies, never racket contacts, and say
nothing about who won a point. They turn solid only when the cloud confirms a rally.

## Pipeline and entry points

| Step | File | Entry |
|---|---|---|
| Decode | `app/src/main/java/com/elevate/app/data/audio/AudioDecoder.kt` | `suspend fun decode(uri: Uri, sink: PcmSink): AudioDecoder.Result` (`Decoded` or `NoAudio`; throws `IOException`) |
| Onsets | `app/src/main/java/com/elevate/app/domain/audio/OnsetDetector.kt` | `OnsetDetector(sampleRateHz, OnsetParams())`, `accept(mono, count, ptsUs)`, `finish(): List<Onset>` |
| Candidates | `app/src/main/java/com/elevate/app/domain/audio/RallyCandidates.kt` | `RallyCandidates.group(onsets, durationMs, RallyParams()): List<RallyCandidate>` |
| All three | `app/src/main/java/com/elevate/app/data/audio/SoundRallyScanner.kt` | `suspend fun scan(uri, durationMsHint = null, originUs = 0, onProgress): Outcome` (`Found(onsets, candidates, decode)` or `NoAudio`) |
| UI state | `app/src/main/java/com/elevate/app/domain/audio/SoundScan.kt` | `SoundScanState`: `Analysing(decodedMs, totalMs)`, `NoAudio`, `Done(onsets, audioMs)`, `Failed(reason)` |
| UI | `app/src/main/java/com/elevate/app/ui/components/SoundTimeline.kt` | `SoundTimeline(durationMs, candidates, positionMs, onSeek: (Long) -> Unit, state)` |

Shapes follow `docs/plan/contracts.md`: `Onset(tMs, strength)` is the §5 `onsets` row
`[t_ms, strength]`; `RallyCandidate(startMs, endMs, onsets)` + `state = "candidate"` is the §2
`rally_candidates` item (`{start_ms, end_ms, onsets, state}`).

## Method

**Decoder.** `MediaExtractor` selects the first `audio/*` track; `MediaCodec` (synchronous buffer
loop, 10 ms dequeue timeouts) decodes it. Each output buffer is down-mixed (channel average) to
mono float and handed to the sink with its `presentationTimeUs`, so time is source presentation
time and stream gaps stay gaps. PCM encodings handled: 16-bit (also when `KEY_PCM_ENCODING` is
absent), float, 8-bit unsigned; others → `IOException`. Checks coroutine cancellation every loop
turn; always `stop()`/`release()` the codec and the extractor. A codec with no input or output
for ~1,000 turns (~10 s) is treated as stalled. Memory is one reusable chunk buffer; the detector
keeps one float + one long per 10 ms frame (≈ 0.6 MB for 8 minutes), never the PCM.
Decode speed is reported as `Decoded.speedX` (audio seconds per wall second) and `wallMs`.

**Onsets** (pure Kotlin, `OnsetParams` defaults):
1. 2nd-order Butterworth high-pass at 2 kHz (RBJ biquad). An impact is a broadband click;
   speech, music and hall hum sit mostly below 2 kHz.
2. Mean-square energy per 10 ms frame, in dB (floor −100 dBFS).
3. Rise = frame dB − dB of the mean energy of the previous 3 frames (30 ms), clipped at 0.
   A log ratio is **gain-invariant**: any recording gain cancels in the difference.
4. Adaptive threshold = median + 6·MAD of the rise over ±1.5 s (recomputed every 250 ms), never
   below 6 dB. Robust to crowd noise and to level.
5. Local maxima above threshold; onsets closer than 80 ms merge (stronger kept).
6. Time = the largest high-passed sample in the peak frame (sub-frame precision).
   Strength = rise / 40 dB, clamped to 0..1, two decimals.

**Candidates** (`RallyParams` defaults): consecutive onsets ≤ 2.5 s apart form a group
(badminton shots are ~0.5–2 s apart); a group needs ≥ 3 onsets (serve + two returns);
padded 0.5 s before and 1 s after; clamped to `[0, duration]`; never overlapping the previous one.

## Verified vs UNVERIFIED

Verified (this session, on this machine):
- `./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest` passes (lint: 0 errors,
  2 pre-existing warnings outside this slice: `DataExtractionRules`, `ObsoleteSdkInt`).
- 20 JVM algorithm unit tests (`OnsetDetectorTest` 10, `RallyCandidatesTest` 10) on signals built
  in code (4 kHz decaying rings at known times over white noise; they are NOT footage): onset times
  within ±10 ms at 48 and 44.1 kHz, times follow buffer PTS, same onsets at ×0.1 gain, noise-only →
  no onsets, steady tone → none, sustained music-like tones → at most their starts and no candidate,
  80 ms merge, digital silence; grouping edges (gap exactly at / just over 2.5 s, < 3 onsets,
  unsorted input, clamping, no overlap).
- MediaCodec/MediaExtractor behaviour used by the decoder, read 2026-10-11 in the platform
  javadoc that the developer.android.com reference is generated from (AOSP
  `frameworks/base/media/java/android/media/MediaCodec.java` and `MediaExtractor.java`, `main`):
  raw audio buffers hold entire frames, one sample per channel in channel order, 16-bit signed
  or float in native byte order; float only when `KEY_PCM_ENCODING` = `ENCODING_PCM_FLOAT` is
  confirmed by the output format, and the documented check defaults a missing key to 16-bit;
  `getOutputBuffer` returns a buffer whose position and limit are the valid output data;
  `setDataSource(FileDescriptor)`: "safe to close as soon as this call returns".
  8-bit output is handled defensively but is not a documented decoder output.

UNVERIFIED (not run here; the lead runs the device test):
- Decoding real clips on the phone (`AudioOnsetDeviceTest`, tag `AudioOnset`), decode speed, and
  whether every phone decoder reports `KEY_PCM_ENCODING` / rate / channel count as used.
- **Accuracy on real matches is unknown** until onsets and candidates are measured against
  Person 4's hand labels. Squeaking shoes, claps, line calls and commentary can produce onsets;
  quiet hits on far-court or phone recordings may be missed. Parameters are physics-based
  starting points, not tuned.
- Audio-to-video time alignment: times are audio buffer PTS. Container edit lists, AAC priming
  and a non-zero first video PTS can offset them. Pass the first presented video frame time as
  `originUs` once measured; check one candidate start against the footage by eye.
- Opus/Vorbis in WebM decode on the target phones (expected, not run).

## Integration (for the lead: ViewModel/screen are outside this slice)

```kotlin
// In the replay/import ViewModel, after the video opens (playback is never delayed):
private val scanner = SoundRallyScanner(app.contentResolver)
val soundState = MutableStateFlow<SoundScanState>(SoundScanState.Analysing(0, null))
val candidates = MutableStateFlow<List<RallyCandidate>>(emptyList())
soundJob?.cancel()
soundJob = viewModelScope.launch(Dispatchers.IO) {
    soundState.value = SoundScanState.Analysing(0, null)
    soundState.value = try {
        // facts: SourceFacts from MediaProbe(resolver).probe(uri), already computed for preflight.
        when (val r = scanner.scan(uri, durationMsHint = facts?.durationMs) { soundState.value = it }) {
            SoundRallyScanner.Outcome.NoAudio -> SoundScanState.NoAudio
            is SoundRallyScanner.Outcome.Found -> { candidates.value = r.candidates; r.state }
        }
    } catch (e: IOException) {
        SoundScanState.Failed(e.message ?: "audio could not be read")
    } catch (e: SecurityException) {
        SoundScanState.Failed("no permission to read this video")
    }
}
// Screen (positionMs: the player's current position, in the same source-presentation ms):
SoundTimeline(
    durationMs = durationMs,
    candidates = vm.candidates.collectAsState().value,
    positionMs = positionMs,
    onSeek = { player.seekTo(it) },
    state = vm.soundState.collectAsState().value,
)
```

`MediaProbe.probe(uri)` already returns `SourceFacts.hasAudio`; skip the scan and show `NoAudio` when it is false.
Cancel the job when the user leaves or picks another video (the decoder stops on cancellation).
Send `candidates` as `rally_candidates` in `POST /v1/sessions` (contracts §2) when it lands.

## Capability flag

Ship behind a flag (e.g. `SOUND_RALLIES`, default **off** in release, on in debug) at tier
`experimental` until the device test has run on the corpus and candidates are measured against
the hand-labelled rallies. Promote to `assisted` only with a recorded recall/precision figure
(with its denominator) in `fixtures/eval/RESULTS.md`.

## A55 device run (lead, 2026-10-11) and decision: flag OFF

`AudioOnsetDeviceTest`, Galaxy A55, real corpus clips, OK (3 tests), all status 0:

| Clip | Audio | Decode speed | Onsets | Candidates |
|---|---|---|---|---|
| `eurogames_bern_1.webm` (25.6 s, match rally footage) | Vorbis 44.1 kHz stereo | 3.2× real time | 38 | 1 (0–25.4 s, 38 onsets) |
| `china_open_2025_final.webm` (505.8 s, broadcast with commentary) | Opus 48 kHz stereo | 6.0× | 1144 | 4, e.g. 66.9–303.2 s (551 onsets) |
| `h13g_singles.mp4` (484.6 s, coaching video with speech) | AAC 44.1 kHz stereo | 7.6× | 883 | 6, e.g. 78.6–419.1 s (580 onsets) |

What this shows: decoding, timing and cancellation work on all three codecs on the phone. The
candidates are **not useful as rally hints** on this footage: speech and commentary produce
onsets throughout, so candidates merge into minutes-long spans, and the hand-labelled
`h13g_singles` rally (40.00–43.98 s) is not isolated (it falls between candidate 1, 0–41.4 s,
and candidate 2, 42.9–55.0 s). No tuning was done on these clips (h13g is the reference clip).

Decision (per the Phase 2 brief: "if audio detection is not sufficiently useful, leave it behind a
capability flag"): the code ships, the `SoundTimeline` is **not wired into the UI**, and
`rally_candidates` is sent as `[]`. Next steps before turning it on: a racket-impact band /
transient-shape check that rejects speech, a maximum candidate length, and recall/precision
against Person 4's rally labels on clips nobody tuned on.
