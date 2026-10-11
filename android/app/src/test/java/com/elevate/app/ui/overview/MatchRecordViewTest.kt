package com.elevate.app.ui.overview

import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.data.events.EventLog
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The Match Overview built from a served `match.v1`. Test vectors (none is a real match):
 *  - the fenced `match.v1` example in docs/plan/contracts.md §7;
 *  - [a55Shape]: the SHAPE of the record served for the A55 run (m_e4ac5c3f…): 14 hit events,
 *    a tier B tap calibration, empty players / rallies / shots / points. Times, H and fractions
 *    here are made up for the test, not copied from that record;
 *  - [newestShape]: the shape the newest engine (#101, #106, #108) and review confirms (#104)
 *    add: players from who_is_me, landing events with `contact_xy_m`, confirmed points. Values made up.
 */
class MatchRecordViewTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/plan/contracts.md").isFile }
    private val example: MatchRecord by lazy {
        val md = File(repo, "docs/plan/contracts.md").readText().replace("\r\n", "\n")
        val block = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(md).map { it.groupValues[1] }.first { "\"schema\": \"match.v1\"" in it }
        Contracts.json.decodeFromString(MatchRecord.serializer(), block)
    }

    private val tierB = """{"calibration_id": "cal_t", "plane": "court", "template": "badminton_singles", "method": "tap",
        "H": [0.01, 0, -5, 0, 0.02, -3, 0, 0.001, 1], "H_cov": null, "residual_px": 2.3, "visible_fraction": 0.64, "tier": "B"}"""

    private fun hit(i: Int, t: Long) =
        """{"event_id": "e_h$i", "type": "hit", "t_ms": $t, "t_sigma_ms": 20, "image_xy": [640.5, 360.0], "cues": ["trajectory"], "state": "candidate", "evidence": [{"t_ms": $t}]}"""

    private fun landing(i: Int, t: Long, xy: String?, state: String = "candidate") =
        """{"event_id": "e_l$i", "type": "landing", "t_ms": $t, "t_sigma_ms": 15, "image_xy": [600, 500], "cues": ["trajectory"], "state": "$state", "evidence": [{"t_ms": $t}]""" +
            (xy?.let { """, "plane": "court", "contact_xy_m": $it, "cov_m2": [[0.01, 0], [0, 0.02]]""" } ?: "") + "}"

    /** A full match.v1 body; [extra] lets a vector add fields the reader does not know. */
    private fun record(
        players: String = "[]",
        calibration: String = tierB,
        events: String = "[]",
        points: String = "[]",
        score: String = """{"timeline": [], "games": []}""",
        extra: String = "",
    ) = Contracts.json.decodeFromString(
        MatchRecord.serializer(),
        """{"schema": "match.v1", "match_id": "m_t", "session_ids": ["s_t"], "sport": "badminton", "format": "singles",
            "ruleset": {"id": "bwf_3x21", "version": "2025-04"}, "players": $players, "calibration": $calibration,
            "analysis": {"analysis_id": "a_t", "engine_version": "0.1.0", "state": "completed", "models": ["tracknet_v3"],
                         "artifacts": {"ball_track": null, "player_tracks": null}},
            "capabilities": {"court": {"tier": "core", "status": "unknown"}, "ball": {"tier": "core", "status": "unknown"},
                             "reviews": {"tier": "experimental", "status": "unknown"}, "score": {"tier": "assisted", "status": "unknown"}},
            "rallies": [], "shots": [], "events": $events, "reviews": [], "confirmed_points": $points, "score": $score,
            "metrics": [], "insights": [], "moments": [], "clips": [], "corrections": []$extra}""",
    )

    /** 14 hits: 4 + 5 + 4 close together, 1 lone hit (no rally candidate). */
    private val a55Shape by lazy {
        val times = listOf(1_000L, 1_900L, 2_700L, 3_500L, 20_000L, 21_100L, 22_000L, 23_200L, 24_000L, 40_000L, 41_000L, 42_200L, 43_000L, 60_000L)
        record(events = times.mapIndexed { i, t -> hit(i, t) }.joinToString(prefix = "[", postfix = "]"), extra = """, "a_future_field": {"x": 1}""")
    }

    private val players = """[{"player_id": "p_me", "track_ids": [], "label": "you", "identity": "user_confirmed"},
        {"player_id": "p_opp", "track_ids": [], "label": "opponent", "identity": "engine"}]"""

    private val newestShape by lazy {
        record(
            players = players,
            events = "[" + listOf(
                hit(0, 1_000), hit(1, 1_800),
                landing(0, 2_400, "[-0.3, 2.0]"),
                landing(1, 5_000, "[2.5, 11.0]", state = "observed"),
                landing(2, 7_000, null),
                landing(3, 9_000, "[20.0, 3.0]"),
            ).joinToString() + "]",
            points = """[{"confirmed_point_id": "cp_1", "rally_id": "r_1", "winner": "p_me", "by": "p_me", "at": "2026-10-11T10:00:00Z", "review_id": "rv_1"},
                {"confirmed_point_id": "cp_2", "rally_id": "r_2", "winner": "p_opp", "by": "p_me", "at": "2026-10-11T10:01:00Z", "review_id": "rv_2"},
                {"confirmed_point_id": "cp_3", "rally_id": "r_3", "winner": "p_me", "by": "p_me", "at": "2026-10-11T10:02:00Z", "review_id": "rv_3"}]""",
            score = """{"timeline": [], "games": [[21, 17]]}""",
        )
    }

    @Test fun `a55 shape - hits from the record, rally candidates grouped from them, labelled`() {
        val s = OverviewDerivation.derive("Match", a55Shape, null, sourceDurationMs = 96_900)
        assertThat(s.hitTimeline.hits).hasSize(14)
        assertThat(s.hitTimeline.recordSource).isEqualTo("the match record · engine 0.1.0")
        assertThat(s.hitTimeline.timelineMs).isEqualTo(96_900)
        assertThat(HitTimelineText.caption(s.hitTimeline))
            .isEqualTo("14 hits · 3 rallies")
        val r = s.rallies as RalliesState.FromHits
        assertThat(r.rows.map { it.hitCount }).containsExactly(4, 5, 4).inOrder()
        assertThat(r.rows.first().rallyId).isEqualTo("${HIT_RALLY_ID_PREFIX}1")
        assertThat(r.rows.first().startMs to r.rows.first().endMs).isEqualTo(1_000L to 3_500L)
        assertThat(r.sourceLabel).isEqualTo("Grouped from 14 hit candidates in the match record · engine 0.1.0 · the record has no rallies yet · not confirmed")
    }

    @Test fun `a55 shape - court chip, visible fraction, no landings, no players, no score`() {
        val s = OverviewDerivation.derive("Match", a55Shape, null)
        val c = s.court as CourtState.Badminton
        assertThat(c.chip).isEqualTo("Court locked from your 4 taps")
        assertThat(c.detail).isEqualTo("64% of the court in frame")
        assertThat(c.marks).isEmpty()
        assertThat(c.note).isEqualTo("No landings in this match")
        assertThat(s.selectedPlayer).isEqualTo("Player not selected yet")
        assertThat(s.opponent).isNull()
        assertThat(s.score).isEqualTo(ScoreLine("No confirmed points yet", "The score counts only points you confirm", confirmed = false))
    }

    @Test fun `newest shape - landings in court metres with denominators and states`() {
        val c = OverviewDerivation.court(newestShape) as CourtState.Badminton
        // Singles template: x = 0 is the left singles sideline, 0.46 m inside the drawn doubles line.
        assertThat(c.frameOffsetM).isWithin(1e-9).of(0.46)
        assertThat(c.marks.map { it.id }).containsExactly("e_l0", "e_l1").inOrder()
        assertThat(c.marks.first().xM).isEqualTo(-0.3)
        assertThat(c.note).isEqualTo(
            "4 landings · 2 drawn (1 candidate, 1 observed) · 1 without a court position · 1 further than 1 m outside the court, not drawn",
        )
        assertThat(courtLegend(c.marks)).isEqualTo("○ candidate · ● observed")
    }

    @Test fun `only candidate landings are counted as candidates`() {
        val m = record(events = "[" + landing(0, 1_000, "[1.0, 2.0]") + "," + landing(1, 2_000, "[3.0, 9.0]") + "]")
        val c = OverviewDerivation.court(m) as CourtState.Badminton
        assertThat(c.note).isEqualTo("2 landing candidates")
        assertThat(c.marks).hasSize(2)
    }

    @Test fun `doubles template draws without offset`() {
        val m = record(calibration = tierB.replace("badminton_singles", "badminton_doubles"), events = "[" + landing(0, 1_000, "[1.0, 2.0]") + "]")
        assertThat((OverviewDerivation.court(m) as CourtState.Badminton).frameOffsetM).isEqualTo(0.0)
    }

    @Test fun `landings without calibration are counted, not drawn`() {
        val m = record(calibration = "null", events = "[" + landing(0, 1_000, "[1.0, 2.0]") + "]")
        val c = OverviewDerivation.court(m) as CourtState.Badminton
        assertThat(c.marks).isEmpty()
        assertThat(c.note).isEqualTo("1 landing candidate · not drawn: the match is not calibrated")
        assertThat(c.chip).isEqualTo("Court not calibrated")
        assertThat(c.detail).isNull()
    }

    @Test fun `newest shape - you and the opponent, score only from confirmed points`() {
        val s = OverviewDerivation.derive("Match", newestShape, null)
        assertThat(s.selectedPlayer).isEqualTo("You")
        assertThat(s.opponent).isEqualTo("Opponent")
        assertThat(s.score).isEqualTo(ScoreLine("You 2 · Opponent 1", "3 confirmed points · games (you first) 21–17", confirmed = true))
        // Two hits 800 ms apart: one rally candidate from the record's hits.
        assertThat((s.rallies as RalliesState.FromHits).rows.single().hitCount).isEqualTo(2)
    }

    @Test fun `contract example - rally winner side, score and landing event`() {
        val s = OverviewDerivation.derive("Match", example, null)
        val row = (s.rallies as RalliesState.Available).rows.single()
        assertThat(row.winnerSide).isEqualTo(1)
        assertThat(row.winner).isEqualTo("Won by opponent · confirmed")
        assertThat(s.score).isEqualTo(ScoreLine("You 0 · Opponent 1", "1 confirmed point of 1 rally · games (you first) 21–17, 19–21, 21–15", confirmed = true))
        assertThat(s.opponent).isEqualTo("Opponent")
        val c = s.court as CourtState.Badminton
        assertThat(c.chip).isEqualTo("Court locked from your 4 taps")
        assertThat(c.detail).isEqualTo("100% of the court in frame")
        assertThat(c.note).isEqualTo("1 landing candidate")
        assertThat(c.marks.single().xM).isEqualTo(0.36)
        // No hit events in the example: the hit timeline stays whatever the windows gave.
        assertThat(s.hitTimeline.recordSource).isNull()
    }

    @Test fun `a record missing every optional list still decodes and reads as empty`() {
        val m = Contracts.json.decodeFromString(
            MatchRecord.serializer(),
            """{"schema": "match.v1", "match_id": "m_t", "session_ids": ["s_t"], "sport": "badminton", "format": "singles",
                "ruleset": {"id": "bwf_3x21", "version": "2025-04"}, "calibration": null,
                "analysis": {"analysis_id": "a_t", "engine_version": "0.1.0", "state": "completed", "models": [],
                             "artifacts": {"ball_track": null, "player_tracks": null}},
                "capabilities": {"court": {"tier": "core", "status": "unknown"}, "ball": {"tier": "core", "status": "unknown"},
                                 "reviews": {"tier": "experimental", "status": "unknown"}, "score": {"tier": "assisted", "status": "unknown"}},
                "score": {}}""",
        )
        val s = OverviewDerivation.derive("Match", m, null)
        assertThat(s.rallies).isInstanceOf(RalliesState.NotAvailable::class.java)
        assertThat((s.court as CourtState.Badminton).note).isEqualTo("No landings in this match")
        assertThat(s.score!!.confirmed).isFalse()
        assertThat(s.selectedPlayer).isEqualTo("Player not selected yet")
    }

    @Test fun `the live-window view is used only while match v1 is missing`() {
        val live = LiveOverview.derive(
            AnalysisUiState.derive(AnalysisSource.Live("a_t"), EventLog.EMPTY, null, null),
            com.elevate.app.domain.analysis.BallTrack.EMPTY, emptyList(), emptyList(), HitTimelineState.EMPTY, null,
        )
        assertThat(OverviewDerivation.derive("Match", null, null, live = live).live).isNotNull()
        assertThat(OverviewDerivation.derive("Match", a55Shape, null, live = live).live).isNull()
    }
}
