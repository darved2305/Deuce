package com.elevate.app.data.history

import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MatchHistoryRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "a_fc8800d5cd054f7090f94e4493ecfe4f"
    private var now = 1_000L

    private fun setup(): Triple<MatchHistory, MatchHistoryRecorder, File> {
        val f = File(tmp.root, "matches.json")
        val h = MatchHistory(f)
        val r = MatchHistoryRecorder(h, clock = { now }, minIntervalMs = 5_000)
        r.onStarted(
            analysisId = id, sessionId = "s_de11aa34d9ed4834b4ab307e690e5cc2", matchId = null,
            sha256 = "3669343e1837befefe9f77ae3b6a740767e4b6e59054d54b9cbbac1042d786ed", title = "eurogames_bern_1",
            sourceUri = "content://x/1", stagedPath = null, sport = "badminton", durationMs = 25_603, width = 848, height = 464,
            state = "queued",
        )
        return Triple(h, r, f)
    }

    @Test fun `started entry is written with the server state`() {
        val (_, _, f) = setup()
        val e = MatchHistory(f).get(id)!!
        assertThat(e.lastState).isEqualTo("queued")
        assertThat(e.createdAt).isEqualTo(1_000)
        assertThat(e.durationMs).isEqualTo(25_603)
    }

    @Test fun `state, window and engine changes are written at once, repeats are not`() {
        val (_, r, f) = setup()
        now = 1_500
        assertThat(r.onAnalysisState(id, "processing", 0, 0, null)).isTrue()
        assertThat(r.onAnalysisState(id, "processing", 0, 0, null)).isFalse()
        now = 2_000
        assertThat(r.onAnalysisState(id, "partial", 6_000, 1, "0.1.0")).isTrue()
        now = 2_100
        assertThat(r.onAnalysisState(id, "partial", 12_000, 2, "0.1.0")).isTrue()
        val e = MatchHistory(f).get(id)!!
        assertThat(e.windowsDone).isEqualTo(2)
        assertThat(e.coverageMs).isEqualTo(12_000)
        assertThat(e.engineVersion).isEqualTo("0.1.0")
        assertThat(e.updatedAt).isEqualTo(2_100)
    }

    @Test fun `coverage-only change is throttled, then flushed`() {
        val (_, r, f) = setup()
        now = 2_000
        r.onAnalysisState(id, "partial", 6_000, 1, "0.1.0")
        now = 3_000
        assertThat(r.onAnalysisState(id, "partial", 6_500, 1, "0.1.0")).isFalse()
        assertThat(MatchHistory(f).get(id)!!.coverageMs).isEqualTo(6_000)
        r.flush()
        assertThat(MatchHistory(f).get(id)!!.coverageMs).isEqualTo(6_500)
        now = 9_000
        assertThat(r.onAnalysisState(id, "partial", 7_000, 1, "0.1.0")).isTrue()
    }

    @Test fun `a still-loading state never wipes the record`() {
        val (_, r, f) = setup()
        r.onAnalysisState(id, "partial", 25_600, 5, "0.1.0")
        assertThat(r.onAnalysisState(id, null, 0, 0, null)).isFalse()
        // Unknown state but real windows: keeps the last state and engine.
        now = 20_000
        r.onAnalysisState(id, null, 25_600, 5, null)
        val e = MatchHistory(f).get(id)!!
        assertThat(e.lastState).isEqualTo("partial")
        assertThat(e.engineVersion).isEqualTo("0.1.0")
        assertThat(e.status()).isEqualTo(HistoryStatus.ReadyFromWindows)
    }

    @Test fun `unknown analysis is ignored`() {
        val (_, r, _) = setup()
        assertThat(r.onAnalysisState("a_other", "partial", 1, 1, null)).isFalse()
    }

    @Test fun `restart keeps what the events said`() {
        val (h, r, _) = setup()
        r.onAnalysisState(id, "partial", 25_600, 5, "0.1.0")
        now = 50_000
        r.onStarted(id, "s_de11aa34d9ed4834b4ab307e690e5cc2", null, "3669343e", "eurogames_bern_1", "content://x/1", null, "badminton", 25_603, 848, 464, "queued")
        val e = h.get(id)!!
        assertThat(e.lastState).isEqualTo("partial")
        assertThat(e.windowsDone).isEqualTo(5)
        assertThat(e.createdAt).isEqualTo(1_000)
    }

    @Test fun `ui state overload records live analyses only`() {
        val (h, r, _) = setup()
        val live = AnalysisUiState(
            source = AnalysisSource.Live(id), state = AnalysisState.PARTIAL,
            coveredUs = 25_600_000, totalUs = 25_603_000, windowsDone = 5, engineVersion = "0.1.0",
        )
        assertThat(r.onAnalysisState(live)).isTrue()
        assertThat(h.get(id)!!.coverageMs).isEqualTo(25_600)
        val recorded = live.copy(source = AnalysisSource.Recorded("fixture_events.jsonl"), windowsDone = 9)
        assertThat(r.onAnalysisState(recorded)).isFalse()
    }
}
