package com.elevate.app.data.record

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelTest {
    @Test fun `a straight landscape phone reads level`() {
        val roll = Level.rollDegrees(9.81f, 0f, 0f)!!
        assertThat(roll).isWithin(0.01).of(0.0)
        assertThat(Level.quality(roll)).isEqualTo(Level.Quality.Level)
    }

    @Test fun `roll is the tilt about the viewing axis in both landscapes`() {
        val a = Level.rollDegrees(9.81f, 0.51f, 0f)!!   // about 3 degrees one way
        val b = Level.rollDegrees(-9.81f, 0.51f, 0f)!!  // the other landscape, same tilt of the picture
        assertThat(a).isWithin(0.1).of(2.98)
        assertThat(b).isWithin(0.1).of(-2.98)
    }

    @Test fun `quality bands`() {
        assertThat(Level.quality(1.4)).isEqualTo(Level.Quality.Level)
        assertThat(Level.quality(-2.0)).isEqualTo(Level.Quality.Close)
        assertThat(Level.quality(3.5)).isEqualTo(Level.Quality.Tilted)
    }

    @Test fun `a phone lying flat has no roll`() {
        assertThat(Level.rollDegrees(0.2f, 0.3f, 9.8f)).isNull()
    }

    @Test fun `smoothing moves towards the sample`() {
        assertThat(Level.smooth(null, 4.0)).isEqualTo(4.0)
        assertThat(Level.smooth(0.0, 10.0, alpha = 0.5)).isEqualTo(5.0)
    }

    @Test fun `the guide trapezoid is narrower at the far end and inside the frame`() {
        val q = FramingGuide.trapezoid
        assertThat(q.farRight.first - q.farLeft.first).isLessThan(q.nearRight.first - q.nearLeft.first)
        assertThat(q.farLeft.second).isLessThan(q.nearLeft.second)
        listOf(q.farLeft, q.farRight, q.nearRight, q.nearLeft).forEach {
            assertThat(it.first).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(it.second).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
        assertThat(FramingGuide.INSTRUCTION).isEqualTo("Stand behind the baseline. Whole court in view.")
    }
}
