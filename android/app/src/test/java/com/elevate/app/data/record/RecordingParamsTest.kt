package com.elevate.app.data.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingParamsTest {
    private fun caps(heights: Set<Int> = setOf(1080, 720, 480), vararg ranges: FpsRange) =
        DeviceCaptureCaps(heights, ranges.toList(), hasOis = true)

    @Test fun `1080p at 60 when the phone offers both`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(30, 30), FpsRange(60, 60))))
        assertThat(p.quality).isEqualTo(RecordQuality.FHD)
        assertThat(p.fps).isEqualTo(60)
        assertThat(p.fpsRange).isEqualTo(FpsRange(60, 60))
    }

    @Test fun `falls back to 30 when 60 is not offered`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(15, 30), FpsRange(30, 30))))
        assertThat(p.fps).isEqualTo(30)
        assertThat(p.fpsRange).isEqualTo(FpsRange(30, 30))
    }

    @Test fun `a wide range that holds 60 counts as 60`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(15, 60))))
        assertThat(p.fps).isEqualTo(60)
        assertThat(p.fpsRange).isEqualTo(FpsRange(60, 60))
    }

    @Test fun `a phone that tops out below 30 gets its best rate`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(15, 24))))
        assertThat(p.fps).isEqualTo(24)
    }

    @Test fun `no ranges reported falls back to 30`() {
        assertThat(RecordingParamsChooser.choose(caps()).fps).isEqualTo(30)
    }

    @Test fun `resolution falls back and never exceeds 1080`() {
        assertThat(RecordingParamsChooser.chooseQuality(caps(heights = setOf(2160, 1080, 720)))).isEqualTo(RecordQuality.FHD)
        assertThat(RecordingParamsChooser.chooseQuality(caps(heights = setOf(2160, 720)))).isEqualTo(RecordQuality.HD)
        assertThat(RecordingParamsChooser.chooseQuality(caps(heights = setOf(2160)))).isEqualTo(RecordQuality.SD)
        assertThat(RecordingParamsChooser.chooseQuality(caps(heights = emptySet()))).isEqualTo(RecordQuality.SD)
    }

    @Test fun `a short target gets the ceiling bitrate`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(60, 60))), targetMinutes = 2)
        assertThat(p.videoBitrateBps).isEqualTo(RecordingParamsChooser.ceilingBps(RecordQuality.FHD, 60))
        assertThat(p.targetFits).isTrue()
    }

    @Test fun `the default target stays inside the 300 MiB cap`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(60, 60))))
        assertThat(p.estimatedBytes(p.maxDurationS)).isAtMost(RecordingParamsChooser.VIDEO_BYTE_CAP)
        // And it fits the length asked for.
        assertThat(p.maxDurationS).isAtLeast(RecordingParamsChooser.DEFAULT_TARGET_MINUTES * 60L)
        assertThat(p.targetFits).isTrue()
    }

    @Test fun `a long target is held at the floor bitrate and reported as not fitting`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(60, 60))), targetMinutes = 60)
        assertThat(p.videoBitrateBps).isEqualTo(RecordingParamsChooser.floorBps(RecordQuality.FHD, 60))
        assertThat(p.targetFits).isFalse()
        assertThat(p.maxDurationS).isLessThan(60 * 60L)
        assertThat(p.estimatedBytes(p.maxDurationS)).isAtMost(RecordingParamsChooser.VIDEO_BYTE_CAP)
    }

    @Test fun `every quality and rate keeps the floor under the ceiling and inside the cap at max duration`() {
        for (q in RecordQuality.entries) for (fps in listOf(24, 30, 60)) {
            assertThat(RecordingParamsChooser.floorBps(q, fps)).isLessThan(RecordingParamsChooser.ceilingBps(q, fps))
            for (minutes in listOf(1, 5, 8, 15, 30, 120)) {
                val caps = DeviceCaptureCaps(setOf(q.height), listOf(FpsRange(fps, fps)), hasOis = false)
                val p = RecordingParamsChooser.choose(caps, minutes)
                assertThat(p.estimatedBytes(p.maxDurationS)).isAtMost(RecordingParamsChooser.VIDEO_BYTE_CAP)
                assertThat(p.videoBitrateBps).isAtLeast(RecordingParamsChooser.floorBps(q, fps))
                assertThat(p.videoBitrateBps).isAtMost(RecordingParamsChooser.ceilingBps(q, fps))
            }
        }
    }

    @Test fun `the duration estimate shown to the user is whole minutes of the max`() {
        val p = RecordingParamsChooser.choose(caps(ranges = arrayOf(FpsRange(30, 30))), targetMinutes = 8)
        assertThat(p.maxDurationMinutes).isEqualTo((p.maxDurationS / 60).toInt())
        assertThat(p.maxDurationMinutes).isAtLeast(8)
    }
}
