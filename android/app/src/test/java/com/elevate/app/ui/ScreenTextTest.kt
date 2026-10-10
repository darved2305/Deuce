package com.elevate.app.ui

import com.elevate.app.TestVectors
import com.elevate.app.data.fixture.TrailLabelsParser
import com.elevate.app.domain.repository.BackendStatus
import com.elevate.app.ui.screens.backendDescription
import com.elevate.app.ui.screens.displayTitle
import com.elevate.app.ui.screens.formatClock
import com.elevate.app.ui.screens.preflightTitle
import com.elevate.app.ui.screens.trailSummary
import com.elevate.app.ui.screens.videoSubtitle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenTextTest {
    @Test fun `numeric MediaStore names become Your match, real names lose only the extension`() {
        assertThat(displayTitle("1000219389.mp4")).isEqualTo("Your match")
        assertThat(displayTitle("1000219389")).isEqualTo("Your match")
        assertThat(displayTitle(null)).isEqualTo("Your match")
        assertThat(displayTitle("  ")).isEqualTo("Your match")
        assertThat(displayTitle("Sunday doubles.mp4")).isEqualTo("Sunday doubles")
        assertThat(displayTitle("VID_20261010_1203.mp4")).isEqualTo("VID_20261010_1203")
    }

    @Test fun `subtitle uses the short side, rounded fps and whole-second duration`() {
        assertThat(videoSubtitle(1920, 1080, 50.0, 484_600)).isEqualTo("1080p50 · 8:04")
        assertThat(videoSubtitle(1080, 1920, 29.97, 61_000)).isEqualTo("1080p30 · 1:01")
    }

    @Test fun `subtitle leaves out what is not known`() {
        assertThat(videoSubtitle(1920, 1080, null, 484_600)).isEqualTo("1080p · 8:04")
        assertThat(videoSubtitle(null, null, null, 484_600)).isEqualTo("8:04")
        assertThat(videoSubtitle(null, null, null, null)).isNull()
    }

    @Test fun `clock shows hours only when needed`() {
        assertThat(formatClock(0)).isEqualTo("0:00")
        assertThat(formatClock(3_729_000)).isEqualTo("1:02:09")
    }

    @Test fun `trail summary keeps the labelled-frame denominator`() {
        val t = TrailLabelsParser.parse(TestVectors.doc(TestVectors.FRAMES_50FPS))
        val s = trailSummary(t)
        assertThat(s.title).isEqualTo("Hand-labelled trail")
        assertThat(s.value).isEqualTo("4/5")
        assertThat(s.spoken).isEqualTo("shuttle seen in 4 of 5 labelled frames")
    }

    @Test fun `clip check title`() {
        assertThat(preflightTitle(blocked = false, findings = 0)).isEqualTo("Clip check · no issues")
        assertThat(preflightTitle(blocked = false, findings = 2)).isEqualTo("Clip check · 2 to note")
        assertThat(preflightTitle(blocked = true, findings = 1)).isEqualTo("Can't be analysed")
    }

    @Test fun `cloud latency and reason stay in the spoken description`() {
        assertThat(backendDescription(BackendStatus.Online(123))).isEqualTo("Elevate cloud online, 123 ms")
        assertThat(backendDescription(BackendStatus.Offline("timeout"))).isEqualTo("Elevate cloud offline: timeout")
    }
}
