package com.elevate.app.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveRowTextTest {
    @Test fun `candidate rows never say the shuttle was seen`() {
        val t = liveRowText(candidates = true, frames = 40, framesInRange = 120)
        assertThat(t.title).isEqualTo("Shuttle trail")
        assertThat(t.spoken).isEqualTo("a candidate on 40 of 120 frames")
        assertThat(t.detail).isEqualTo("From your upload · more appears as the analysis runs")
        assertThat(t.spoken + t.detail).doesNotContain("huttle on")
    }

    @Test fun `observed rows say shuttle on N of M frames`() {
        val t = liveRowText(candidates = false, frames = 101, framesInRange = 150)
        assertThat(t.title).isEqualTo("Shuttle trail")
        assertThat(t.spoken).isEqualTo("shuttle on 101 of 150 frames")
        assertThat(t.detail).isEqualTo("From your upload · more appears as the analysis runs")
    }
}
