package com.elevate.app.domain.training

import com.elevate.app.domain.analysis.BallTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShuttleCueTest {
    private data class Row(val tMs: Long, val x: Float, val y: Float, val state: Byte = BallTrack.OBSERVED, val conf: Float = 0.9f)

    private fun track(vararg rows: Row) = BallTrack(
        LongArray(rows.size) { rows[it].tMs * 1000 },
        FloatArray(rows.size) { rows[it].x },
        FloatArray(rows.size) { rows[it].y },
        FloatArray(rows.size) { rows[it].conf },
        ByteArray(rows.size) { rows[it].state },
        ByteArray(rows.size),
        listOf("v"),
    )

    /** Shuttle moving +600 px/s in x and -300 px/s in y, one row every 20 ms from t=1000. */
    private fun moving(upTo: Long = 1200) = (1000L..upTo step 20).map { Row(it, 100f + (it - 1000) * 0.6f, 500f - (it - 1000) * 0.3f) }.toTypedArray()

    @Test fun `no row after the freeze is used`() {
        val cue = ShuttleCue.at(1100, track(*moving(1200), Row(1120, 9999f, 9999f)))!!
        // Newest used row is t=1100: x = 100 + 100*0.6 = 160.
        assertThat(cue.x).isWithin(0.01f).of(160f)
        assertThat(cue.y).isWithin(0.01f).of(470f)
        assertThat(cue.tail.all { it.tMs < 1100 }).isTrue()
        // Row exactly at the freeze is the frozen frame itself and is allowed.
        assertThat(ShuttleCue.at(1100, track(Row(1080, 0f, 0f), Row(1100, 5f, 5f)))!!.x).isEqualTo(5f)
        // A future row cannot rescue a sparse past.
        assertThat(ShuttleCue.at(1100, track(Row(1080, 0f, 0f), Row(1120, 5f, 5f)))).isNull()
    }

    @Test fun `direction and speed come from the motion`() {
        val cue = ShuttleCue.at(1100, track(*moving()))!!
        assertThat(cue.vxPxPerS).isWithin(0.5f).of(600f)
        assertThat(cue.vyPxPerS).isWithin(0.5f).of(-300f)
        assertThat(cue.speedPxPerS).isWithin(0.5f).of(670.82f)
        assertThat(cue.dirX).isWithin(1e-3f).of(0.8944f)
        assertThat(cue.dirY).isWithin(1e-3f).of(-0.4472f)
        assertThat(cue.hasDirection).isTrue()
    }

    @Test fun `two points give the plain difference`() {
        val cue = ShuttleCue.at(1040, track(Row(1020, 10f, 10f), Row(1040, 10f, 30f)))!!
        assertThat(cue.vxPxPerS).isWithin(1e-3f).of(0f)
        assertThat(cue.vyPxPerS).isWithin(1e-3f).of(1000f)
        assertThat(cue.dirY).isWithin(1e-4f).of(1f)
        assertThat(cue.tail).hasSize(1)
    }

    @Test fun `only the last 200 ms are used and the tail is capped`() {
        val rows = (800L..1100L step 20).map { Row(it, it.toFloat(), 0f) }.toTypedArray()
        val cue = ShuttleCue.at(1100, track(*rows))!!
        assertThat(cue.tail).hasSize(ShuttleCue.MAX_TAIL)
        assertThat(cue.tail.map { it.tMs }).containsExactly(1040L, 1060L, 1080L).inOrder()
    }

    @Test fun `smoothing damps one noisy row`() {
        // True motion is 600 px/s in x (x = 100 + 0.6 * (t - 1000)); the row at 1060 is 12 px off.
        val rows = arrayOf(Row(1000, 100f, 0f), Row(1020, 112f, 0f), Row(1040, 124f, 0f), Row(1060, 136f + 12f, 0f), Row(1080, 148f, 0f))
        val cue = ShuttleCue.at(1080, track(*rows))!!
        val lastTwo = (148f - 148f) / 0.02f // what the last two rows alone say: 0 px/s
        assertThat(kotlin.math.abs(cue.vxPxPerS - 600f)).isLessThan(kotlin.math.abs(lastTwo - 600f))
        assertThat(cue.vxPxPerS).isWithin(150f).of(600f)
    }

    @Test fun `sparse data gives null`() {
        assertThat(ShuttleCue.at(1100, BallTrack.EMPTY)).isNull()
        assertThat(ShuttleCue.at(1100, track(Row(1100, 1f, 1f)))).isNull()
        // Two rows but one is older than the window.
        assertThat(ShuttleCue.at(1100, track(Row(850, 1f, 1f), Row(1100, 2f, 2f)))).isNull()
        // Newest row too far before the freeze: not where the shuttle is in the frame.
        assertThat(ShuttleCue.at(1300, track(Row(1100, 1f, 1f), Row(1120, 2f, 2f)))).isNull()
    }

    @Test fun `unobserved rows are skipped`() {
        val rows = arrayOf(
            Row(1000, 0f, 0f, BallTrack.CANDIDATE), Row(1020, 0f, 0f, BallTrack.INFERRED),
            Row(1040, 5f, 5f, BallTrack.USER), Row(1060, 7f, 7f), Row(1080, 9f, 9f, BallTrack.CANDIDATE),
        )
        assertThat(ShuttleCue.at(1080, track(*rows))).isNull() // one observed row only
        val cue = ShuttleCue.at(1080, track(Row(1040, 10f, 0f), Row(1050, 99f, 99f, BallTrack.CANDIDATE), Row(1060, 20f, 0f)))!!
        assertThat(cue.x).isEqualTo(20f)
        assertThat(cue.vxPxPerS).isWithin(1e-2f).of(500f)
    }

    @Test fun `nearly still shuttle has no direction`() {
        val cue = ShuttleCue.at(1040, track(Row(1000, 10f, 10f), Row(1020, 10f, 10f), Row(1040, 10.01f, 10f)))!!
        assertThat(cue.hasDirection).isFalse()
        assertThat(cue.dirX).isEqualTo(0f)
    }

    @Test fun `same timestamp keeps the most confident row`() {
        val cue = ShuttleCue.at(1040, track(Row(1020, 0f, 0f), Row(1040, 50f, 0f, conf = 0.2f), Row(1040, 20f, 0f, conf = 0.9f)))!!
        assertThat(cue.x).isEqualTo(20f)
    }

    @Test fun `fit transform maps image pixels like the video`() {
        // 1920x1080 video in a 960x540 box: scale 0.5, no offset.
        val a = FitTransform.of(1920, 1080, 960f, 540f)!!
        assertThat(a.mapX(1920f)).isWithin(1e-3f).of(960f)
        assertThat(a.mapY(540f)).isWithin(1e-3f).of(270f)
        // Portrait 1080x1920 in the same box: height-limited, centred horizontally with bars.
        val b = FitTransform.of(1080, 1920, 960f, 540f)!!
        assertThat(b.scale).isWithin(1e-4f).of(0.28125f)
        assertThat(b.mapX(0f)).isWithin(1e-3f).of(328.125f)
        assertThat(b.mapY(0f)).isWithin(1e-3f).of(0f)
        assertThat(FitTransform.of(0, 10, 10f, 10f)).isNull()
    }
}
