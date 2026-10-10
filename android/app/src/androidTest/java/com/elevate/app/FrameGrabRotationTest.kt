package com.elevate.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elevate.app.data.media.MediaProbe
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Calibration taps are stored in upright source pixels (contracts §0). This checks, on real files,
 * that the frame [MediaMetadataRetriever] hands back is already upright: its size equals the
 * probe's display size (coded size swapped for 90/270). Uses the corpus clips in `files/corpus/`.
 */
@RunWith(AndroidJUnit4::class)
class FrameGrabRotationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "corpus")

    @Test fun grabbedFramesAreUpright() {
        val clips = dir.listFiles { f -> f.name.endsWith(".mp4") && !f.name.startsWith("corrupt") }.orEmpty()
            .filter { it.length() < 100L * 1024 * 1024 } // small clips keep the test quick
        assumeTrue("no corpus clips in $dir", clips.isNotEmpty())
        var rotated = 0
        var nulls = 0
        for (f in clips) {
            val facts = runBlocking { MediaProbe(ctx.contentResolver).probe(Uri.fromFile(f)) }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                // Middle of the clip. Null is a real outcome on this A55 (a 1.2 s Dolby Vision clip): the
                // calibration screen must handle it, so it is logged, not hidden.
                val atUs = facts.durationMs * 500
                val bmp = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp == null) {
                    Log.i("FrameGrab", "${f.name}: NO FRAME at $atUs us (${facts.codec}, hdr ${facts.isHdr})")
                    nulls++
                    continue
                }
                Log.i("FrameGrab", "${f.name}: coded ${facts.width}x${facts.height} rot ${facts.rotation} hdr ${facts.isHdr} -> bitmap ${bmp.width}x${bmp.height}")
                assertWithMessage(f.name).that(bmp.width to bmp.height).isEqualTo(facts.displayWidth to facts.displayHeight)
                if (facts.rotation % 180 != 0) rotated++
                bmp.recycle()
            } finally {
                r.release()
            }
        }
        Log.i("FrameGrab", "checked ${clips.size} clips, $rotated rotated 90/270 and upright, $nulls without a frame")
        assertWithMessage("no rotated clip was verified").that(rotated).isAtLeast(1)
    }
}
