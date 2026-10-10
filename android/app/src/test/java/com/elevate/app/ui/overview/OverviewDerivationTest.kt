package com.elevate.app.ui.overview

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.events.EventLog
import com.elevate.app.data.fixture.TrailLabelsParser
import com.elevate.app.TestVectors
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/**
 * Overview and analysing-screen derivations. The match record is the fenced `match.v1` example in
 * docs/plan/contracts.md §7 (a contract example used as a test vector, not a real match).
 */
class OverviewDerivationTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/plan/contracts.md").isFile }
    private val example: MatchRecord by lazy {
        val md = File(repo, "docs/plan/contracts.md").readText().replace("\r\n", "\n")
        val block = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(md).map { it.groupValues[1] }.first { "\"schema\": \"match.v1\"" in it }
        Contracts.json.decodeFromString(MatchRecord.serializer(), block)
    }

    @Test fun `no match record means rallies not available and an empty court`() {
        val s = OverviewDerivation.derive("Match", null, null)
        assertThat(s.rallies).isInstanceOf(RalliesState.NotAvailable::class.java)
        assertThat((s.rallies as RalliesState.NotAvailable).reason).startsWith("Rallies not available yet")
        val court = s.court as CourtState.Badminton
        assertThat(court.marks).isEmpty()
        assertThat(court.note).isEqualTo("No landings yet")
        assertThat(s.selectedPlayer).isEqualTo("Player not selected yet")
        assertThat(s.source).isEqualTo(OverviewSource.Nothing)
    }

    @Test fun `a record without rallies says so`() {
        val r = OverviewDerivation.rallies(example.copy(rallies = emptyList()), null, null)
        assertThat((r as RalliesState.NotAvailable).reason).isEqualTo("Rallies not available yet: the match record has none")
    }

    @Test fun `rallies carry ball coverage with a frame denominator when frames are known`() {
        // 30 fps frames over the example's rally (18.3–26.9 s).
        val frames = LongArray(1000) { 18_000_000L + it * 33_333L }
        val r = OverviewDerivation.rallies(example, frames, 60_000) as RalliesState.Available
        val row = r.rows.single()
        val expected = frames.count { it in 18_300_000L..26_900_000L }
        assertThat(row.ballCoverage).isEqualTo("ball seen in 61% of $expected frames")
        assertThat(row.shots).isEqualTo(1)
        assertThat(row.winner).isEqualTo("Won by opponent · confirmed")
        assertThat(row.winnerConfirmed).isTrue()
        assertThat(r.timelineMs).isEqualTo(60_000)
        assertThat(r.sourceLabel).contains("engine 0.3.0")
    }

    @Test fun `ball coverage without frames still states what it is a fraction of`() {
        assertThat(OverviewDerivation.ballCoverageLabel(0.61, null)).isEqualTo("ball seen in 61% of frames")
        assertThat(OverviewDerivation.ballCoverageLabel(1.7, 10)).isEqualTo("ball coverage invalid in record")
    }

    @Test fun `unconfirmed winners are model estimates`() {
        val m = example.copy(rallies = example.rallies.map { it.copy(winnerState = "candidate") })
        val row = (OverviewDerivation.rallies(m, null, null) as RalliesState.Available).rows.single()
        assertThat(row.winner).isEqualTo("Winner candidate: opponent · MODEL ESTIMATE")
        assertThat(row.winnerConfirmed).isFalse()
    }

    @Test fun `court draws only real landings, with counts and denominators`() {
        val c = OverviewDerivation.court(example) as CourtState.Badminton
        assertThat(c.singles).isTrue()
        assertThat(c.marks.single().xM).isEqualTo(3.0)
        assertThat(c.note).isEqualTo("1 landing of 1 shot (1 observed)")
        val far = example.copy(shots = example.shots.map { it.copy(chosen = it.chosen.copy(landingXyM = listOf(20.0, 3.0))) })
        val cf = OverviewDerivation.court(far) as CourtState.Badminton
        assertThat(cf.marks).isEmpty()
        assertThat(cf.note).isEqualTo("No landings to draw from 1 shot")
        val none = OverviewDerivation.court(example.copy(shots = emptyList())) as CourtState.Badminton
        assertThat(none.note).isEqualTo("No landings yet")
        assertThat(OverviewDerivation.court(example.copy(sport = "table_tennis"))).isInstanceOf(CourtState.NotApplicable::class.java)
    }

    @Test fun `court proportions are the BWF dimensions`() {
        assertThat(OverviewDerivation.COURT_LENGTH_M).isEqualTo(13.40)
        assertThat(OverviewDerivation.COURT_WIDTH_M).isEqualTo(6.10)
        assertThat(OverviewDerivation.SINGLES_WIDTH_M).isEqualTo(5.18)
    }

    @Test fun `source line and selected player come from the record`() {
        assertThat(OverviewDerivation.sourceLine(example)).isEqualTo("Badminton · singles · bwf_3x21 2025-04")
        assertThat(OverviewDerivation.selectedPlayer(example)).isEqualTo("You · 1 track · confirmed by you")
    }

    @Test fun `hand-labelled fixture is labelled as a reference, with its own counts`() {
        val track = TrailLabelsParser.parse(TestVectors.doc(TestVectors.FRAMES_50FPS))
        val s = OverviewDerivation.derive("Match", null, null, fixture = "fixture_trail.json" to track)
        assertThat(s.source.badge).isEqualTo("Hand-labelled reference · @test")
        assertThat(s.referenceDetail).isEqualTo("40.00–40.10 s · shuttle marked in 4 of 5 labelled frames · 6 frames in range")
        assertThat(s.referenceRange).isEqualTo(40_000L..40_100L)
        assertThat(OverviewDerivation.thumbnailTimeMs(null, track, null)).isEqualTo(40_050)
    }

    @Test fun `thumbnail time is a real time from the record`() {
        assertThat(OverviewDerivation.thumbnailTimeMs(example, null, null)).isEqualTo((18_300 + 26_900) / 2)
        assertThat(OverviewDerivation.thumbnailTimeMs(null, null, 10_000)).isEqualTo(5_000)
    }

    private fun ev(seq: Long, type: String, emit: List<Long>? = null, ws: Int? = null, reason: String? = null) =
        AnalysisEvent("e_$seq", "a_t", "m_t", seq, 1, type, "2026-10-11T10:00:%02dZ".format(seq * 5), null, ws, emit, emit, null, reason)

    private fun ui(vararg e: AnalysisEvent, total: Long? = 48_500_000) =
        AnalysisUiState.derive(AnalysisSource.Live("a_t"), EventLog.EMPTY.plus(e.toList()), null, total)

    @Test fun `analysing text uses real seconds of a known length, never a percentage`() {
        val s = ui(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "window.completed", listOf(0, 12_000_000), 0), ev(4, "window.failed", listOf(12_000_000, 18_000_000), 1, "decode_error"))
        assertThat(AnalysingText.coverage(s)).isEqualTo("12.0 of 48.5 s analysed")
        assertThat(AnalysingText.coverage(s)).doesNotContain("%")
        assertThat(AnalysingText.windows(s)).isEqualTo("1 window done · 1 failed")
        assertThat(AnalysingText.failureLine(s.windowFailures.single())).isEqualTo("Window 1 (12.0–18.0 s): decode_error")
        assertThat(AnalysingText.title(s)).isEqualTo("Analysing · first results ready")
        assertThat(AnalysingText.primaryAction(s)).isEqualTo(AnalysingText.Action.OPEN_PARTIAL)
        assertThat(AnalysingText.downloads(s)).isEqualTo("0 of 1 window results downloaded")
    }

    @Test fun `stage times are the events' own times`() {
        assertThat(AnalysingText.stageTime("2026-10-11T10:00:15Z", "2026-10-11T10:00:05Z", ZoneOffset.UTC)).isEqualTo("10:00:15 · +10 s")
        assertThat(AnalysingText.stageTime("2026-10-11T10:02:15Z", "2026-10-11T10:00:05Z", ZoneOffset.UTC)).isEqualTo("10:02:15 · +2 min 10 s")
        assertThat(AnalysingText.stageTime("not a time", null)).isEqualTo("not a time")
    }

    @Test fun `failure shows its reason and offers retry`() {
        val s = ui(ev(1, "analysis.queued"), ev(2, "analysis.failed", reason = "dispatch_failed"))
        assertThat(s.state).isEqualTo(AnalysisState.FAILED)
        assertThat(s.failureReason).isEqualTo("dispatch_failed")
        assertThat(AnalysingText.primaryAction(s)).isEqualTo(AnalysingText.Action.RETRY)
        assertThat(AnalysingText.facts(s)).isEqualTo("state failed · event 2")
    }

    @Test fun `connection notices`() {
        val base = ui(ev(1, "analysis.queued"))
        assertThat(AnalysingText.connection(base)).isNull()
        assertThat(AnalysingText.connection(base.copy(offline = true))!!.first).isEqualTo("Waiting for connection")
        assertThat(AnalysingText.connection(base.copy(isCatchingUp = true))!!.first).isEqualTo("Catching up")
        assertThat(AnalysingText.connection(base.copy(unresolvedGapSeq = 2))!!.first).isEqualTo("Event 2 is missing")
        val before = AnalysisUiState(AnalysisSource.Live("a_t"), null, isCatchingUp = true)
        assertThat(AnalysingText.title(before)).isEqualTo("Waiting for the server")
        assertThat(AnalysingText.connection(before)!!.first).isEqualTo("Connecting")
        assertThat(AnalysingText.sourceBadge(before.copy(source = AnalysisSource.Recorded("fixture_events.jsonl"))))
            .isEqualTo("Recorded event file (fixture_events.jsonl) · not a live analysis")
    }

    @Test fun `unknown clip length gives no denominator`() {
        val s = ui(ev(1, "window.completed", listOf(0, 6_000_000), 0), total = null)
        assertThat(AnalysingText.coverage(s)).isEqualTo("6.0 s analysed · clip length unknown")
    }
}
