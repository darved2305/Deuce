package com.elevate.app.data

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.events.EventLog
import com.elevate.app.data.events.EventReplay
import com.elevate.app.data.fixture.FixtureSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Test vectors only (shapes from contracts §3); not a recording of any real analysis. */
class EventLogTest {

    private fun ev(seq: Long, type: String, emit: List<Long>? = null, at: String = "2026-10-10T18:00:0${seq}Z", reason: String? = null) =
        AnalysisEvent("e_$seq", "a_t", "m_t", seq, 1, type, at, "0.3.0", windowSeq = emit?.let { seq.toInt() }, emitRangeUs = emit, reason = reason)

    private val okLine = """{"event_id":"e_1","analysis_id":"a_t","match_id":"m_t","seq":1,"schema_version":1,"type":"analysis.queued","created_at":"2026-10-10T18:00:00Z","engine_version":"0.3.0"}"""

    @Test fun `out of order and duplicate events apply in seq order once`() {
        val log = EventLog.EMPTY.plus(listOf(ev(2, "analysis.started"), ev(1, "analysis.queued"), ev(2, "analysis.started")))
        assertThat(log.applied.map { it.seq }).containsExactly(1L, 2L).inOrder()
        assertThat(log.afterSeq).isEqualTo(2)
        assertThat(log.state).isEqualTo("processing")
    }

    @Test fun `a gap stops application until the missing seq arrives`() {
        val gap = EventLog.EMPTY.plus(listOf(ev(1, "analysis.queued"), ev(3, "window.completed", listOf(0, 6_000_000))))
        assertThat(gap.afterSeq).isEqualTo(1)
        assertThat(gap.missingSeq).isEqualTo(2)
        assertThat(gap.coverageUs).isEmpty()
        val filled = gap.plus(listOf(ev(2, "analysis.started")))
        assertThat(filled.afterSeq).isEqualTo(3)
        assertThat(filled.missingSeq).isNull()
        assertThat(filled.coverageUs).containsExactly(0L..6_000_000L)
    }

    @Test fun `coverage merges overlapping emit ranges and only from window completed`() {
        val log = EventLog.EMPTY.plus(
            listOf(
                ev(1, "analysis.started"),
                ev(2, "window.completed", listOf(0, 6_000_000)),
                ev(3, "window.completed", listOf(6_000_000, 12_000_000)),
                ev(4, "window.failed", listOf(12_000_000, 18_000_000), reason = "decode"),
                ev(5, "window.completed", listOf(18_000_000, 24_000_000)),
            ),
        )
        assertThat(log.coverageUs).containsExactly(0L..12_000_000L, 18_000_000L..24_000_000L).inOrder()
        assertThat(log.failures.map { it.reason }).containsExactly("decode")
    }

    @Test fun `same seq with different content is rejected`() {
        val log = EventLog.EMPTY.plus(listOf(ev(1, "analysis.queued")))
        assertThrows(IllegalArgumentException::class.java) { log.plus(listOf(ev(1, "analysis.failed"))) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `replay keeps recorded spacing, divided by speed, capped`() = runTest {
        val events = listOf(
            ev(1, "analysis.queued", at = "2026-10-10T18:00:00Z"),
            ev(2, "analysis.started", at = "2026-10-10T18:00:02Z"),
            ev(3, "analysis.completed", at = "2026-10-10T18:01:00Z"),
        )
        val logs = EventReplay.replay(events, speed = 2.0, maxGapMs = 10_000).toList()
        assertThat(logs.map { it.afterSeq }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(logs.last().state).isEqualTo("completed")
        assertThat(currentTime).isEqualTo(1_000L + 5_000L) // 2 s / 2, then 58 s capped to 10 s, / 2
    }

    @Test fun `jsonl parsing reports the bad line`() {
        assertThat(EventReplay.parseJsonl("$okLine\n\n")).hasSize(1)
        val e = assertThrows(EventReplay.InvalidEvents::class.java) { EventReplay.parseJsonl("$okLine\n{\"seq\":2}") }
        assertThat(e.message).startsWith("line 2")
    }

    @Test fun `fixture source exposes bundled event files and reports invalid ones`() {
        val src = FixtureSource { listOf("fixture_events.jsonl" to okLine, "broken.jsonl" to "nope") }
        assertThat(src.recordedEvents).hasSize(2)
        assertThat(src.recordedEvents[0]).isInstanceOf(FixtureSource.EventsResult.Found::class.java)
        assertThat(src.recordedEvents[1]).isInstanceOf(FixtureSource.EventsResult.Invalid::class.java)
        assertThat(FixtureSource { emptyList() }.recordedEvents).isEmpty()
    }
}
