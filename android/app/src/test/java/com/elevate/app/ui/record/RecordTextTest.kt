package com.elevate.app.ui.record

import com.elevate.app.data.record.DeviceCaptureCaps
import com.elevate.app.data.record.FpsRange
import com.elevate.app.data.record.NetworkState
import com.elevate.app.data.record.RecordingParamsChooser
import com.elevate.app.data.record.SegmentUploadFailure
import com.elevate.app.data.record.SegmentUploadStatus
import com.elevate.app.data.record.UploadNetworkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordTextTest {
    private val wifi = NetworkState(online = true, unmetered = true)

    @Test fun `elapsed time and size`() {
        assertThat(RecordText.elapsed(0)).isEqualTo("0:00")
        assertThat(RecordText.elapsed(75_400_000)).isEqualTo("1:15")
        assertThat(RecordText.sizeLine(126L * 1024 * 1024)).isEqualTo("126 MB of 300 MB")
    }

    @Test fun `the parameter line states resolution rate and minutes`() {
        val p = RecordingParamsChooser.choose(DeviceCaptureCaps(setOf(1080, 720), listOf(FpsRange(60, 60)), true))
        assertThat(RecordText.paramsLine(p)).isEqualTo("1080p, 60 fps, up to ${p.maxDurationMinutes} min")
    }

    @Test fun `the live line counts confirmed pieces and analysis`() {
        val policy = UploadNetworkPolicy.WifiOnly
        assertThat(RecordText.uploadLine(SegmentUploadStatus(), policy, wifi)).isEqualTo("No pieces sent yet")
        assertThat(RecordText.uploadLine(SegmentUploadStatus(recorded = 2, sent = 1), policy, wifi)).isEqualTo("1 piece sent")
        assertThat(RecordText.uploadLine(SegmentUploadStatus(recorded = 4, sent = 3, analysisStarted = true), policy, wifi))
            .isEqualTo("3 pieces sent, analysis started")
    }

    @Test fun `waiting for wifi is shown only while pieces are waiting`() {
        val mobile = NetworkState(online = true, unmetered = false)
        assertThat(RecordText.uploadLine(SegmentUploadStatus(recorded = 2, sent = 0), UploadNetworkPolicy.WifiOnly, mobile)).contains("Wi-Fi")
        assertThat(RecordText.uploadLine(SegmentUploadStatus(recorded = 0, sent = 0), UploadNetworkPolicy.WifiOnly, mobile)).isEqualTo("No pieces sent yet")
    }

    @Test fun `a failure message wins`() {
        val s = SegmentUploadStatus(recorded = 1, failure = SegmentUploadFailure("part_failed", "Piece 0 could not be sent after 4 tries.", 0, true))
        assertThat(RecordText.uploadLine(s, UploadNetworkPolicy.WifiOnly, wifi)).contains("could not be sent")
    }
}
