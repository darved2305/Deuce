package com.elevate.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekTargetTest {
    @Test fun `a seek right after open (duration unknown) keeps the rally start instead of 0`() {
        // openRally on a video that is not open yet: open() resets the duration to 0, then seeks.
        assertThat(seekTargetMs(83_400, durationMs = 0)).isEqualTo(83_400)
    }

    @Test fun `a known duration still clamps both ends`() {
        assertThat(seekTargetMs(83_400, durationMs = 60_000)).isEqualTo(60_000)
        assertThat(seekTargetMs(-5, durationMs = 60_000)).isEqualTo(0)
        assertThat(seekTargetMs(12_000, durationMs = 60_000)).isEqualTo(12_000)
    }

    @Test fun `negative targets floor at 0 without a duration`() {
        assertThat(seekTargetMs(-1, durationMs = 0)).isEqualTo(0)
    }
}
