package com.elevate.app.data.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SegmentPlannerTest {
    private val params = RecordingParamsChooser.choose(
        DeviceCaptureCaps(setOf(1080), listOf(FpsRange(60, 60)), true),
    )

    @Test fun `pieces roll at the engine window length`() {
        assertThat(SegmentPlanner.TARGET_SEGMENT_US).isEqualTo(6_000_000L)
        assertThat(SegmentPlanner.shouldRoll(5_999_999)).isFalse()
        assertThat(SegmentPlanner.shouldRoll(6_000_000)).isTrue()
    }

    @Test fun `segment counts round up`() {
        assertThat(SegmentPlanner.segmentCount(0)).isEqualTo(0)
        assertThat(SegmentPlanner.segmentCount(1)).isEqualTo(1)
        assertThat(SegmentPlanner.segmentCount(6_000_000)).isEqualTo(1)
        assertThat(SegmentPlanner.segmentCount(6_000_001)).isEqualTo(2)
        assertThat(SegmentPlanner.segmentCount(60_000_000)).isEqualTo(10)
    }

    @Test fun `a full piece is far below the server's per-segment cap`() {
        val bytes = SegmentPlanner.expectedSegmentBytes(params.totalBitrateBps)
        assertThat(bytes).isLessThan(RecordingParamsChooser.VIDEO_BYTE_CAP / 10)
        assertThat(bytes).isGreaterThan(1_000_000L)
    }

    @Test fun `the file size limit is what is left of the budget minus the margin`() {
        val cap = RecordingParamsChooser.VIDEO_BYTE_CAP
        val maxSeg = cap
        assertThat(SegmentPlanner.nextFileSizeLimit(0, maxSeg)).isEqualTo(cap - SegmentPlanner.CAP_MARGIN_BYTES)
        assertThat(SegmentPlanner.nextFileSizeLimit(100L * 1024 * 1024, maxSeg))
            .isEqualTo(cap - 100L * 1024 * 1024 - SegmentPlanner.CAP_MARGIN_BYTES)
        assertThat(SegmentPlanner.nextFileSizeLimit(cap, maxSeg)).isEqualTo(0)
        // A server that allows smaller pieces wins.
        assertThat(SegmentPlanner.nextFileSizeLimit(0, 50L * 1024 * 1024)).isEqualTo(50L * 1024 * 1024)
    }

    @Test fun `recording stops at the byte budget and at the max duration`() {
        val cap = RecordingParamsChooser.VIDEO_BYTE_CAP
        assertThat(SegmentPlanner.stopReason(1_000_000, 10_000_000, params)).isNull()
        assertThat(SegmentPlanner.stopReason(cap - SegmentPlanner.CAP_MARGIN_BYTES, 10_000_000, params)).isEqualTo(StopReason.Budget)
        assertThat(SegmentPlanner.stopReason(1_000_000, params.maxDurationS * 1_000_000, params)).isEqualTo(StopReason.MaxDuration)
    }

    @Test fun `the planned maximum never reaches the byte stop before the duration stop`() {
        val atMax = params.estimatedBytes(params.maxDurationS)
        assertThat(atMax + SegmentPlanner.CAP_MARGIN_BYTES).isAtMost(RecordingParamsChooser.VIDEO_BYTE_CAP + SegmentPlanner.CAP_MARGIN_BYTES)
        assertThat(SegmentPlanner.stopReason(atMax, (params.maxDurationS - 1) * 1_000_000, params)).isNull()
    }

    @Test fun `the timeline is continuous`() {
        val spans = SegmentPlanner.timeline(listOf(6_010_000, 6_000_000, 2_500_000))
        assertThat(spans.map { it.ptsStartUs }).containsExactly(0L, 6_010_000L, 12_010_000L).inOrder()
        assertThat(spans.map { it.ptsEndUs }).containsExactly(6_010_000L, 12_010_000L, 14_510_000L).inOrder()
        assertThat(spans.map { it.seq }).containsExactly(0, 1, 2).inOrder()
        assertThat(spans.sumOf { it.durationUs }).isEqualTo(14_510_000L)
    }

    @Test fun `a piece with no duration is refused`() {
        try {
            SegmentPlanner.timeline(listOf(6_000_000, 0))
            error("expected failure")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("piece 1")
        }
    }
}
