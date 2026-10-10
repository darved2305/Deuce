package com.elevate.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elevate.app.data.media.MediaProbe
import com.elevate.app.domain.media.Preflight
import com.elevate.app.domain.media.Severity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * [MediaProbe] on the real Phase 1 corpus, against the ffprobe numbers in
 * `android/proof/video-manifest.md`. Needs the clips in the app's files dir under `corpus/`:
 * `adb shell cp /sdcard/Movies/Elevate/<clip> /sdcard/Android/data/com.elevate.app/files/corpus/`.
 * A missing clip skips its check.
 */
@RunWith(AndroidJUnit4::class)
class MediaProbeCorpusTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "corpus")
    private val probe = MediaProbe(ctx.contentResolver)

    /** name, codec, w, h, frames, median/min/max interval µs, gaps, stated rate (video-manifest.md). */
    private data class Expect(val name: String, val codec: String, val w: Int, val h: Int, val frames: Int, val median: Long, val min: Long, val max: Long, val gaps: Int, val fps: Double)

    private val corpus = listOf(
        Expect("h13g_singles.mp4", "avc1", 1920, 1080, 24_227, 20_000, 20_000, 20_000, 0, 50.0),
        Expect("china_open_2025_final.webm", "vp09", 1920, 1080, 30_349, 17_000, 16_000, 17_000, 0, 60.0),
        Expect("sport_en_france_4k.webm", "vp09", 3840, 2160, 16_129, 40_000, 40_000, 40_000, 0, 25.0),
        Expect("eurogames_bern_1.webm", "vp08", 848, 464, 767, 33_000, 33_000, 67_000, 1, 30.0),
        Expect("eurogames_bern_2.webm", "vp08", 848, 464, 2_904, 33_000, 33_000, 67_000, 3, 30.0),
        Expect("vfr_tabletennis_handheld.webm", "vp09", 1080, 1920, 573, 38_000, 38_000, 39_000, 0, 26.0),
    )

    @Test fun corpusMatchesFfprobe() {
        assumeTrue("no corpus dir $dir", dir.isDirectory)
        val present = corpus.filter { File(dir, it.name).isFile }
        assertWithMessage("corpus clips missing from $dir").that(present.map { it.name }).containsExactlyElementsIn(corpus.map { it.name })
        for (e in present) {
            val t0 = System.nanoTime()
            val f = runBlocking { probe.probe(Uri.fromFile(File(dir, e.name))) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val p = f.pts!!
            android.util.Log.i("MediaProbeCorpus", "${e.name}: ${f.codec} ${f.width}x${f.height} rot ${f.rotation} frames ${p.frames} " +
                "median ${p.medianIntervalUs} min ${p.minUs} max ${p.maxUs} gaps ${p.gaps} vfr ${p.vfr} audio ${f.hasAudio}/${f.audioRateHz} " +
                "decodable ${f.decodable} findings ${Preflight.assess(f).map { it.code }} in $ms ms")
            val m = assertWithMessage(e.name)
            m.that(f.codec).isEqualTo(e.codec)
            m.that(f.width to f.height).isEqualTo(e.w to e.h)
            m.that(p.frames).isEqualTo(e.frames)
            m.that(p.medianIntervalUs).isEqualTo(e.median)
            m.that(p.minUs).isEqualTo(e.min)
            m.that(p.maxUs).isEqualTo(e.max)
            m.that(p.gaps).isEqualTo(e.gaps)
            m.that(f.decodable).isTrue()
            m.that(f.isHdr).isFalse()
            // Measured rate from the mean spacing; the manifest states ~60 fps for 16/17 ms WebM, ~26 for 38/39 ms.
            m.that(p.fpsMeasured!!).isWithin(0.5).of(e.fps)
        }
    }

    @Test fun truncatedFileIsRejected() {
        val f = File(dir, "corrupt_truncated_h13g.mp4")
        assumeTrue(f.isFile)
        // Either the extractor refuses it, or what it reads must block analysis (the moov atom is cut off).
        val facts = try { runBlocking { probe.probe(Uri.fromFile(f)) } } catch (e: IOException) { return }
        android.util.Log.i("MediaProbeCorpus", "truncated: $facts findings ${Preflight.assess(facts)}")
        assertThat(Preflight.assess(facts).map { it.severity }).contains(Severity.Block)
    }
}
