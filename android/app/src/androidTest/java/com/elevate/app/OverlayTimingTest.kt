package com.elevate.app

import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elevate.app.data.fixture.TrailLabelsParser
import com.elevate.app.playback.PlayerController
import com.elevate.app.playback.TimeMapping
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Real footage through the real decoder and the real Media3 effect pipeline. Needs the working
 * clip on the device (not in git):
 *   adb push h13g_singles.mp4 /sdcard/Android/data/com.elevate.app/files/
 * Skips (does not pass) when it is absent.
 *
 * Proves that the overlay is driven by source presentation timestamps: after every seek, at 0.25×
 * and at 1×, the frames handed to the overlay carry exactly the clip's 20 ms frame grid, and the
 * bundled labels resolve for those frames.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OverlayTimingTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var controller: PlayerController
    private lateinit var reader: ImageReader
    private val thread = HandlerThread("frames").apply { start() }
    /** presentationTimeUs of every frame the overlay drew into (processed, possibly ahead of display). */
    private val frames = CopyOnWriteArrayList<Long>()

    private fun onMain(block: () -> Unit) = instr.runOnMainSync(block)

    /** ExoPlayer must be read on its application (main) thread. */
    private fun ready(): Boolean {
        var r = false
        onMain { r = controller.player.playbackState == Player.STATE_READY }
        return r
    }

    @Before fun setUp() {
        val clip = File(ctx.getExternalFilesDir(null), "h13g_singles.mp4")
        assumeTrue("push h13g_singles.mp4 to ${clip.parent}", clip.exists())
        reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(thread.looper))
        onMain {
            controller = PlayerController(ctx)
            controller.overlay.onFrame = { frames += it }
            controller.player.setVideoSurface(reader.surface)
            controller.open(Uri.fromFile(clip))
            controller.player.volume = 0f
            controller.pause()
        }
    }

    @After fun tearDown() {
        if (::controller.isInitialized) onMain { controller.release() }
        if (::reader.isInitialized) reader.close()
        thread.quitSafely()
    }

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out; frames seen: ${frames.takeLast(5)}" }
            Thread.sleep(10)
        }
    }

    /** Seeks while paused; returns the first frame the overlay drew after the seek. */
    private fun seekAndAwaitFrame(ms: Long): Long {
        frames.clear()
        onMain { controller.seekTo(ms) }
        waitFor { frames.isNotEmpty() }
        Thread.sleep(300) // let the pipeline settle (it may process the next frame ahead)
        return frames.first()
    }

    @Test fun seeksLandOnTheSourceFrameGrid() {
        waitFor { ready() }
        // Forward, backward, forward again: the first frame drawn after each seek is the target frame.
        for (ms in listOf(41_000L, 40_500L, 43_980L, 40_000L, 42_400L)) {
            val pts = seekAndAwaitFrame(ms)
            assertEquals("first frame drawn after seekTo($ms)", ms * 1000, pts)
            android.util.Log.i("OverlayTimingTest", "seek $ms: drawn=${frames.toList()}")
            assertTrue("every drawn frame is on the 20 ms grid", frames.all { it % 20_000 == 0L })
        }
    }

    @Test fun playbackAtQuarterAndFullSpeedDeliversEveryFrameInOrder() {
        waitFor { ready() }
        for (speed in listOf(0.25f, 1f)) {
            seekAndAwaitFrame(40_000)
            frames.clear()
            onMain { controller.setSpeed(speed); controller.player.play() }
            waitFor(20_000) { frames.size >= 50 }
            onMain { controller.pause() }
            val f = frames.toList()
            val steps = f.zipWithNext { a, b -> b - a }.toSet()
            assertEquals("frame spacing at ${speed}x (µs)", setOf(20_000L), steps)
            assertTrue("starts at the seek target", f.first() in 40_000_000L..40_040_000L)
        }
    }

    @Test fun bundledLabelsResolveForTheDecodedFrames() {
        val text = ctx.assets.open("fixtures/fixture_trail.json").bufferedReader().use { it.readText() }
        val track = TrailLabelsParser.parse(text)
        val tol = TimeMapping.matchToleranceUs(track.tUs)
        waitFor { ready() }
        var matched = 0
        for (i in 0 until track.labelledFrames step 10) {
            val ms = track.tUs[i] / 1000
            val pts = seekAndAwaitFrame(ms)
            assertEquals("label $i resolves for its decoded frame", i, TimeMapping.labelIndexAt(track, pts, tol))
            matched++
        }
        assertTrue(matched >= 10)
    }

    @Test fun togglingTheTrailWhilePausedRedrawsTheSameFrame() {
        waitFor { ready() }
        seekAndAwaitFrame(43_300)
        Thread.sleep(500)
        for (visible in listOf(false, true, false)) {
            frames.clear()
            onMain { controller.setOverlayVisible(visible) }
            waitFor { frames.isNotEmpty() }
            assertEquals("redraw after setOverlayVisible($visible) is the held frame", 43_300_000L, frames.first())
            Thread.sleep(500)
        }
    }

    /** Measurement, logged under tag OverlayTimingTest: open → first overlay frame, and seek → frame. */
    @Test fun measureOpenAndSeekLatency() {
        val clip = Uri.fromFile(File(ctx.getExternalFilesDir(null), "h13g_singles.mp4"))
        val open = mutableListOf<Long>()
        repeat(5) {
            onMain { controller.player.stop() }
            Thread.sleep(500) // drain frames still in flight from the previous open
            frames.clear()
            val t0 = System.nanoTime()
            onMain { controller.open(clip); controller.pause() }
            waitFor { frames.any { it == 0L } } // the clip's first presented frame
            open += (System.nanoTime() - t0) / 1_000_000
        }
        waitFor { ready() }
        val seek = mutableListOf<Long>()
        for (ms in listOf(40_000L, 120_000L, 41_000L, 300_000L, 43_000L)) {
            frames.clear()
            val t0 = System.nanoTime()
            onMain { controller.seekTo(ms) }
            waitFor { frames.isNotEmpty() }
            seek += (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(200)
        }
        android.util.Log.i("OverlayTimingTest", "open->first frame ms=$open seek->frame ms=$seek")
    }
}
