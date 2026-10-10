package com.elevate.app.domain.analysis

import com.elevate.app.data.api.WindowQuality
import com.elevate.app.data.api.WindowResult
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Test vectors only (window_result.v1 shape, contracts §5); not engine output for any video. */
class WindowResultStoreTest {

    private fun row(vararg v: Any?) = JsonArray(v.map { x -> when (x) { null -> JsonNull; is Number -> JsonPrimitive(x); else -> JsonPrimitive(x.toString()) } })

    private fun w(seq: Int, emit: List<Long>, decode: List<Long> = emit, vararg rows: JsonArray, analysis: String = "a_t") = WindowResult(
        "window_result.v1", analysis, seq, decode, emit, listOf("tracknet_v3"), rows.toList(), emptyList(), emptyList(), emptyList(), WindowQuality(0.5, 0.0, 0.0),
    )

    @Test fun `each window keeps only its emit range, so overlaps never double count`() {
        val s = WindowResultStore("a_t", 1920, 1080)
        // Window 0 decodes 0..6.75 s but owns 0..6 s; window 1 decodes 5.25..12.75 s, owns 6..12 s.
        s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_750_000), row(5_500, 1.0, 1.0, 0.9, "observed"), row(6_500, 2.0, 2.0, 0.9, "observed")), "0.3.0")
        s.put(w(1, listOf(6_000_000, 12_000_000), listOf(5_250_000, 12_750_000), row(5_500, 9.0, 9.0, 0.9, "observed"), row(6_500, 2.5, 2.5, 0.9, "observed")), "0.3.0")
        val t = s.track()
        assertThat(t.tUs.toList()).containsExactly(5_500_000L, 6_500_000L).inOrder()
        assertThat(t.x.toList()).containsExactly(1.0f, 2.5f).inOrder()
        assertThat(s.droppedOutsideEmit).isEqualTo(2)
        assertThat(s.coverageUs).containsExactly(0L..12_000_000L)
    }

    @Test fun `a row on a shared boundary belongs to the later window`() {
        val s = WindowResultStore("a_t")
        s.put(w(1, listOf(6_000_000, 12_000_000), listOf(6_000_000, 12_000_000), row(6_000, 7.0, 7.0, 0.8, "observed")), "0.3.0")
        s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(6_000, 3.0, 3.0, 0.8, "observed")), "0.3.0")
        assertThat(s.track().x.toList()).containsExactly(7.0f)
    }

    @Test fun `arrival order does not matter`() {
        val a = WindowResultStore("a_t")
        val b = WindowResultStore("a_t")
        val w0 = w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(100, 1.0, 1.0, 0.9, "observed"))
        val w1 = w(1, listOf(6_000_000, 12_000_000), listOf(6_000_000, 12_000_000), row(7_000, 2.0, 2.0, null, "inferred"))
        a.put(w0, "0.3.0"); a.put(w1, "0.3.0")
        b.put(w1, "0.3.0"); b.put(w0, "0.3.0")
        assertThat(a.track().tUs.toList()).isEqualTo(b.track().tUs.toList())
        assertThat(a.track().state.toList()).isEqualTo(b.track().state.toList())
    }

    @Test fun `re-delivery of a window replaces it, an older engine version never wins`() {
        val s = WindowResultStore("a_t")
        s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(100, 1.0, 1.0, 0.9, "observed")), "0.3.0")
        val again = s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(200, 2.0, 2.0, 0.9, "observed")), "0.3.0")
        assertThat((again as WindowResultStore.PutResult.Stored).replaced).isTrue()
        assertThat(s.track().tUs.toList()).containsExactly(200_000L)
        val old = s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(300, 3.0, 3.0, 0.9, "observed")), "0.2.9")
        assertThat(old).isInstanceOf(WindowResultStore.PutResult.KeptNewer::class.java)
        assertThat(s.track().tUs.toList()).containsExactly(200_000L)
        assertThat(WindowResultStore.compareVersions("0.10.0", "0.9.1")).isGreaterThan(0)
    }

    @Test fun `invalid rows are rejected and counted, never repaired`() {
        val s = WindowResultStore("a_t", 1920, 1080)
        val r = s.put(
            w(
                0, listOf(0, 6_000_000), listOf(0, 6_000_000),
                row(100, 1.0, 1.0, 0.9, "observed"),
                row(110, Double.NaN, 1.0, 0.9, "observed"),
                row(120, 2000.0, 1.0, 0.9, "observed"),
                row(130, 1.0, 1.0, 1.5, "observed"),
                row(140, 1.0, 1.0, 0.9, "seen"),
                row(150, 1.0, 1.0, 0.9),
                row("160", 1.0, 1.0, 0.9, "observed"),
            ),
            "0.3.0",
        ) as WindowResultStore.PutResult.Stored
        assertThat(r.window.rows).hasSize(1)
        assertThat(r.window.rejected).isEqualTo(6)
        assertThat(s.rejectedRows).isEqualTo(6)
    }

    @Test fun `a window for another analysis or with a bad range is refused whole`() {
        val s = WindowResultStore("a_t")
        assertThat(s.put(w(0, listOf(0, 6_000_000), analysis = "a_other"), "0.3.0")).isInstanceOf(WindowResultStore.PutResult.Rejected::class.java)
        assertThat(s.put(w(0, listOf(6_000_000, 0)), "0.3.0")).isInstanceOf(WindowResultStore.PutResult.Rejected::class.java)
        assertThat(s.stored).isEmpty()
    }

    @Test fun `a failed window stays a gap and coverage counts only stored windows`() {
        val s = WindowResultStore("a_t")
        s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(100, 1.0, 1.0, 0.9, "observed")), "0.3.0")
        s.markFailed(1, "decode_error")
        s.put(w(2, listOf(12_000_000, 18_000_000), listOf(12_000_000, 18_000_000), row(12_100, 1.0, 1.0, 0.9, "observed")), "0.3.0")
        assertThat(s.coverageUs).containsExactly(0L..6_000_000L, 12_000_000L..18_000_000L).inOrder()
        assertThat(AnalysisUiState.coveredUs(s.coverageUs)).isEqualTo(12_000_000)
        assertThat(s.failedWindows).containsExactly(1, "decode_error")
        assertThat(s.track().indexAt(9_000_000, 20_000)).isEqualTo(-1)
    }

    @Test fun `binary search finds rows within tolerance only`() {
        val s = WindowResultStore("a_t")
        s.put(w(0, listOf(0, 6_000_000), listOf(0, 6_000_000), row(100, 1.0, 1.0, 0.9, "observed"), row(133, 2.0, 2.0, null, "inferred")), "0.3.0")
        val t = s.track()
        assertThat(t.indexAt(133_000)).isEqualTo(1)
        assertThat(t.indexAt(120_000, 5_000)).isEqualTo(-1)
        assertThat(t.indexAt(131_000, 5_000)).isEqualTo(1)
        assertThat(t.conf[1].isNaN()).isTrue()
        assertThat(t.count(BallTrack.INFERRED)).isEqualTo(1)
    }
}
