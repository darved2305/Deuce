package com.elevate.app.domain.review

import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.MatchRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewLandingsTest {
    private fun cal(tier: String = "B", template: String = "badminton_singles") =
        """{"calibration_id": "cal_t", "plane": "court", "template": "$template", "method": "tap",
            "H": [0.01, 0, -5, 0, 0.02, -3, 0, 0.001, 1], "H_cov": null, "residual_px": 2.3, "visible_fraction": 0.64, "tier": "$tier"}"""

    private fun landing(id: String, t: Long, xy: String?, cov: String? = "[[0.0004, 0], [0, 0.0009]]", plane: String = "court", image: String = "[640.5, 400.0]") =
        """{"event_id": "$id", "type": "landing", "t_ms": $t, "t_sigma_ms": 15, "image_xy": $image, "cues": ["trajectory"], "state": "candidate",
            "plane": "$plane", "evidence": [{"t_ms": $t}]""" + (xy?.let { """, "contact_xy_m": $it""" } ?: "") + (cov?.let { """, "cov_m2": $it""" } ?: "") + "}"

    private val hit = """{"event_id": "e_h", "type": "hit", "t_ms": 900, "t_sigma_ms": 20, "image_xy": [1, 2], "cues": [], "state": "candidate", "evidence": []}"""

    private fun record(events: List<String>, calibration: String = cal(), format: String = "singles", sport: String = "badminton") = Contracts.json.decodeFromString(
        MatchRecord.serializer(),
        """{"schema": "match.v1", "match_id": "m_t", "session_ids": ["s_t"], "sport": "$sport", "format": "$format",
            "ruleset": {"id": "bwf_3x21", "version": "2025-04"}, "players": [], "calibration": $calibration,
            "analysis": {"analysis_id": "a_t", "engine_version": "0.1.0", "state": "completed", "models": [],
                         "artifacts": {"ball_track": null, "player_tracks": null}},
            "capabilities": {"court": {"tier": "core", "status": "unknown"}, "ball": {"tier": "core", "status": "unknown"},
                             "reviews": {"tier": "experimental", "status": "unknown"}, "score": {"tier": "assisted", "status": "unknown"}},
            "rallies": [], "shots": [], "events": ${events.joinToString(prefix = "[", postfix = "]")}, "confirmed_points": []}""",
    )

    @Test fun `only landings with a court position are listed, in time order, with real fields`() {
        val m = record(listOf(
            hit,
            landing("e_b", 5_000, "[2.59, 13.3]"),
            landing("e_none", 3_000, null),
            landing("e_a", 2_000, "[5.3, 6.0]"),
        ))
        val l = ReviewLandings.from(m)
        assertThat(l.map { it.eventId }).containsExactly("e_a", "e_b").inOrder()
        assertThat(l[0].call.verdict).isEqualTo(Verdict.Outside)
        assertThat(l[0].call.line).isEqualTo(BoundaryLine.SideRight)
        assertThat(l[0].imageXy).isEqualTo(640.5 to 400.0)
        assertThat(l[1].call.sigmaM).isWithin(1e-9).of(0.03) // sqrt(0.0009) along y for the back line
        assertThat(l[1].call.usedFallbackSigma).isFalse()
    }

    @Test fun `no landings with positions means no entry`() {
        assertThat(ReviewLandings.from(null)).isEmpty()
        assertThat(ReviewLandings.from(record(listOf(hit)))).isEmpty()
        assertThat(ReviewLandings.from(record(listOf(landing("e", 1, null))))).isEmpty()
        assertThat(ReviewLandings.from(record(listOf(landing("e", 1, "[2.0, 3.0]")), sport = "tennis"))).isEmpty()
    }

    @Test fun `grade C and missing calibration give not seen, not a guess`() {
        val c = ReviewLandings.from(record(listOf(landing("e", 1, "[2.59, 6.7]")), calibration = cal(tier = "C"))).single()
        assertThat(c.call.verdict).isEqualTo(Verdict.NotSeen)
        val none = ReviewLandings.from(record(listOf(landing("e", 1, "[2.59, 6.7]")), calibration = "null")).single()
        assertThat(none.call.notSeen).isEqualTo(NotSeenReason.NoCalibration)
    }

    @Test fun `missing cov_m2 falls back to the grade's error`() {
        val l = ReviewLandings.from(record(listOf(landing("e", 1, "[2.59, 13.0]", cov = null)))).single()
        assertThat(l.call.usedFallbackSigma).isTrue()
        assertThat(l.call.sigmaM).isWithin(1e-9).of(0.40)
    }

    @Test fun `doubles matches judge against the doubles sidelines`() {
        // x = 0.2 is outside the singles line (0.46) but inside the doubles line (0).
        val dbl = ReviewLandings.from(record(listOf(landing("e", 1, "[0.2, 6.7]")), calibration = cal(template = "badminton_doubles"), format = "doubles")).single()
        assertThat(dbl.call.verdict).isEqualTo(Verdict.Inside)
        val sgl = ReviewLandings.from(record(listOf(landing("e", 1, "[0.2, 6.7]")), calibration = cal(template = "badminton_doubles"), format = "singles")).single()
        assertThat(sgl.call.verdict).isEqualTo(Verdict.Outside)
    }

    @Test fun `frame time snaps to the nearest real frame, rounded up to a seekable ms`() {
        val frames = longArrayOf(0, 20_000, 40_000, 60_000)
        assertThat(ReviewLandings.frameMs(frames, 39)).isEqualTo(40)
        assertThat(ReviewLandings.frameMs(frames, 49)).isEqualTo(40)
        assertThat(ReviewLandings.frameMs(frames, 51)).isEqualTo(60)
        assertThat(ReviewLandings.frameMs(frames, 500)).isEqualTo(60)
        assertThat(ReviewLandings.frameMs(frames, 0)).isEqualTo(0)
        assertThat(ReviewLandings.frameMs(longArrayOf(33_366), 30)).isEqualTo(34)
        assertThat(ReviewLandings.frameMs(null, 1234)).isEqualTo(1234)
        assertThat(ReviewLandings.frameMs(LongArray(0), 1234)).isEqualTo(1234)
    }
}
