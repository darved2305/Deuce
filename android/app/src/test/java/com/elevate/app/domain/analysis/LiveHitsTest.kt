package com.elevate.app.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Test vectors shaped like contracts §5 hit events; not a recording of any real analysis. */
class LiveHitsTest {

    private fun hit(tMs: Long, xy: Pair<Double, Double>? = 100.0 to 200.0, state: String = "candidate") =
        HitCandidate(tMs, 12, "t_1", xy, listOf("trajectory"), state)

    private val sha = "ab12cd"

    @Test fun `nothing is shown unless the open file is the analysed file`() {
        val hits = listOf(hit(1_000))
        assertThat(LiveHits.forFile(hits, sha, "ff00ff")).isEmpty()
        assertThat(LiveHits.forFile(hits, null, sha)).isEmpty()
        assertThat(LiveHits.forFile(hits, sha, null)).isEmpty()
        assertThat(LiveHits.forFile(hits, "", "")).isEmpty()
        assertThat(LiveHits.forFile(hits, sha, sha.uppercase())).hasSize(1)
    }

    @Test fun `sorted by time with identical duplicates collapsed`() {
        val r = LiveHits.forFile(listOf(hit(3_000), hit(1_000), hit(3_000), hit(2_000, null)), sha, sha)
        assertThat(r.map { it.tMs }).containsExactly(1_000L, 2_000L, 3_000L).inOrder()
        assertThat(r[0]).isEqualTo(LiveHit(1_000, 100f to 200f, 12, "candidate"))
        assertThat(r[1].imageXy).isNull()
    }

    @Test fun `hits outside the clip are dropped`() {
        val r = LiveHits.forFile(listOf(hit(-5), hit(0), hit(10_000), hit(10_001)), sha, sha, durationMs = 10_000)
        assertThat(r.map { it.tMs }).containsExactly(0L, 10_000L).inOrder()
    }

    @Test fun `an off-image position is dropped but the hit keeps its time`() {
        val r = LiveHits.forFile(
            listOf(hit(1, 1921.0 to 10.0), hit(2, 10.0 to 1081.0), hit(3, -1.0 to 5.0), hit(4, Double.NaN to 5.0), hit(5, 1920.0 to 1080.0)),
            sha, sha, imageW = 1920, imageH = 1080,
        )
        assertThat(r.map { it.imageXy }).containsExactly(null, null, null, null, 1920f to 1080f).inOrder()
    }

    @Test fun `indicesNear finds hits within the window inclusive`() {
        val t = longArrayOf(1_000, 1_150, 1_300, 2_000)
        assertThat(LiveHits.indicesNear(t, 1_150, 150)).isEqualTo(0..2)
        assertThat(LiveHits.indicesNear(t, 1_151, 150)).isEqualTo(1..2)
        assertThat(LiveHits.indicesNear(t, 1_600, 150).isEmpty()).isTrue()
        assertThat(LiveHits.indicesNear(t, 2_150, 150)).isEqualTo(3..3)
        assertThat(LiveHits.indicesNear(LongArray(0), 0, 150).isEmpty()).isTrue()
    }
}
