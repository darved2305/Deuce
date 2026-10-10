package com.elevate.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elevate.app.data.audio.AudioDecoder
import com.elevate.app.data.audio.SoundRallyScanner
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Sound onsets and rally candidates on real clips from the app's files dir under `corpus/`
 * (see MediaProbeCorpusTest for how they get there). A missing clip skips its test.
 *
 * Logs (tag "AudioOnset") the onset count, every candidate and the decode speed, so accuracy
 * can be judged by eye against the footage. Asserts only robust facts: decoding finishes, every
 * time lies inside the decoded span, onsets are in time order, candidates are ordered and
 * non-overlapping. It does NOT assert how many onsets or candidates a clip has: that is unknown
 * until measured against labels.
 */
@RunWith(AndroidJUnit4::class)
class AudioOnsetDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "corpus")

    @Test fun h13gSingles() = scan("h13g_singles.mp4")

    @Test fun euroGamesBern1() = scan("eurogames_bern_1.webm")

    @Test fun chinaOpen2025Final() = scan("china_open_2025_final.webm")

    private fun scan(name: String) {
        val f = File(dir, name)
        assumeTrue("missing $f", f.isFile)
        val scanner = SoundRallyScanner(ctx.contentResolver)
        var progressReports = 0
        val t0 = System.nanoTime()
        val outcome = runBlocking(Dispatchers.IO) { scanner.scan(Uri.fromFile(f)) { progressReports++ } }
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        if (outcome is SoundRallyScanner.Outcome.NoAudio) {
            Log.i(TAG, "$name: no audio track ($wallMs ms)")
            return
        }
        val found = outcome as SoundRallyScanner.Outcome.Found
        val d: AudioDecoder.Result.Decoded = found.decode
        Log.i(
            TAG,
            "$name: ${d.mime} ${d.sampleRateHz} Hz ${d.channels} ch pcm ${d.pcmEncoding}, decoded ${d.decodedMs} ms " +
                "(pts ${d.firstPtsUs}..${d.endUs} µs, track ${d.trackDurationUs} µs) in ${d.wallMs} ms = " +
                String.format(java.util.Locale.ROOT, "%.1f", d.speedX) + "x real time; scan $wallMs ms; " +
                "${found.onsets.size} onsets, ${found.candidates.size} candidates, $progressReports progress reports",
        )
        found.candidates.forEachIndexed { i, r -> Log.i(TAG, "$name candidate ${i + 1}: ${r.startMs}..${r.endMs} ms, ${r.onsets} onsets, ${r.state}") }
        Log.i(TAG, "$name onsets [t_ms, strength]: " + found.onsets.joinToString(" ") { "[${it.tMs},${it.strength}]" })

        val m = assertWithMessage(name)
        m.that(d.samples).isGreaterThan(0L)
        m.that(d.decodedMs).isGreaterThan(0L)
        val loMs = d.firstPtsUs / 1000 - 1
        val hiMs = d.endUs / 1000 + 1
        for (o in found.onsets) {
            m.that(o.tMs).isAtLeast(loMs)
            m.that(o.tMs).isAtMost(hiMs)
            m.that(o.strength.toDouble()).isAtLeast(0.0)
            m.that(o.strength.toDouble()).isAtMost(1.0)
        }
        m.that(found.onsets.map { it.tMs }).isInOrder()
        var prevEnd = Long.MIN_VALUE
        for (r in found.candidates) {
            m.that(r.state).isEqualTo("candidate")
            m.that(r.startMs).isAtLeast(0L)
            m.that(r.startMs).isAtMost(r.endMs)
            m.that(r.startMs).isAtLeast(prevEnd)
            d.trackDurationUs?.let { m.that(r.endMs).isAtMost(it / 1000) }
            prevEnd = r.endMs
        }
    }

    private companion object {
        const val TAG = "AudioOnset"
    }
}
