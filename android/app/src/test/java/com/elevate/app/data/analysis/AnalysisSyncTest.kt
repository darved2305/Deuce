package com.elevate.app.data.analysis

import com.elevate.app.data.api.AnalysisCreateResponse
import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.AnalysisEventsPage
import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.api.WindowQuality
import com.elevate.app.data.api.WindowResult
import com.elevate.app.domain.analysis.AnalysisState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AnalysisSync against an in-memory server that follows contracts §3 (cursor semantics) and §4–5.
 * Test vectors only: shapes from the contract, not a recording of any real analysis.
 */
class AnalysisSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val id = "a_t"

    private fun ev(seq: Long, type: String, windowSeq: Int? = null, emit: List<Long>? = null, artifact: String? = null, reason: String? = null, engine: String? = "0.3.0") =
        AnalysisEvent("e_$seq", id, "m_t", seq, 1, type, "2026-10-11T10:00:%02dZ".format(seq), engine, windowSeq, emit?.let { listOf(it[0], it[1] + 750_000) }, emit, artifact, reason)

    private fun window(seq: Int, emit: List<Long>, rows: List<List<Any?>>) = WindowResult(
        "window_result.v1", id, seq, emit, emit, listOf("tracknet_v3"),
        rows.map { r -> JsonArray(r.map { v -> when (v) { null -> JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } }) },
        emptyList(), emptyList(), emptyList(), WindowQuality(0.5, 0.0, 0.0),
    )

    /** In-memory server. [hidden] seqs are withheld (a gap) until removed. */
    private class FakeServer : AnalysisClient {
        val events = sortedMapOf<Long, AnalysisEvent>()
        val hidden = mutableSetOf<Long>()
        var state = "queued"
        var offline = false
        var shuffle = false
        var duplicate = false
        val requests = mutableListOf<Long>()
        val artifacts = mutableMapOf<String, WindowResult>()
        var artifactError: AnalysisApiException? = null
        var analysisRow: AnalysisGetResponse? = null

        override suspend fun startAnalysis(sessionId: String, idempotencyKey: String) = StartedAnalysis(AnalysisCreateResponse("a_t", "queued"), true)
        override suspend fun getAnalysis(analysisId: String): AnalysisGetResponse =
            analysisRow ?: throw AnalysisApiException(AnalysisApiException.Kind.HTTP, "not_found: analysis not found", 404, "not_found")

        override suspend fun events(analysisId: String, afterSeq: Long, limit: Int): AnalysisEventsPage {
            if (offline) throw AnalysisApiException(AnalysisApiException.Kind.NETWORK, "no network (host not resolved)")
            requests += afterSeq
            val after = events.values.filter { it.seq > afterSeq && it.seq !in hidden }
            val page = after.take(limit)
            var out = if (shuffle) page.reversed() else page
            if (duplicate) out = out + out
            return AnalysisEventsPage(out, page.lastOrNull()?.seq ?: afterSeq, after.size > limit, state)
        }

        override suspend fun artifactUrl(artifactId: String): SignedUrl {
            artifactError?.let { throw it }
            return SignedUrl("https://r2.example.invalid/$artifactId")
        }

        override suspend fun downloadWindowResult(url: SignedUrl): WindowResult =
            artifacts[url.value.substringAfterLast('/')] ?: throw AnalysisApiException(AnalysisApiException.Kind.HTTP, "window result download failed: HTTP 404", 404)

        override suspend fun getMatch(matchId: String): MatchRecord = throw AnalysisApiException(AnalysisApiException.Kind.HTTP, "not_found: route not found", 404, "not_found")

        fun add(vararg e: AnalysisEvent) = e.forEach { events[it.seq] = it }
    }

    private fun sync(server: FakeServer, store: CursorStore? = null, pageLimit: Int = 100, totalUs: Long? = 48_500_000) =
        AnalysisSync(id, server, store, totalUs, 1920, 1080, PollPolicy(activeMs = 1_000, idleMaxMs = 4_000, errorBaseMs = 1_000, errorMaxMs = 4_000), pageLimit) { 1_000L }

    @Test fun `pages until has_more is false and applies events in seq order`() = runTest {
        val s = FakeServer().apply {
            add(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "window.completed", 0, listOf(0, 6_000_000)), ev(4, "analysis.partial"))
            state = "partial"
        }
        val sync = sync(s, pageLimit = 2)
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.NEW_EVENTS)
        assertThat(s.requests).containsExactly(0L, 2L).inOrder() // second page has has_more false
        val st = sync.state.value
        assertThat(st.state).isEqualTo(AnalysisState.PARTIAL)
        assertThat(st.lastSeq).isEqualTo(4)
        assertThat(st.isCatchingUp).isFalse()
        assertThat(st.coveredUs).isEqualTo(6_000_000)
        assertThat(st.totalUs).isEqualTo(48_500_000)
        assertThat(st.stages.map { it.type }).containsExactly("analysis.queued", "analysis.started", "window.completed", "analysis.partial").inOrder()
        assertThat(st.stages.first().createdAt).isEqualTo("2026-10-11T10:00:01Z")
        assertThat(st.engineVersion).isEqualTo("0.3.0")
    }

    @Test fun `out of order and duplicated pages apply once in order`() = runTest {
        val s = FakeServer().apply { add(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "window.completed", 0, listOf(0, 6_000_000))); shuffle = true; duplicate = true; state = "partial" }
        val sync = sync(s)
        sync.syncOnce()
        assertThat(sync.state.value.lastSeq).isEqualTo(3)
        assertThat(sync.state.value.windowsDone).isEqualTo(1)
        assertThat(sync.state.value.syncError).isNull()
    }

    @Test fun `a gap holds later events and refetches from the last contiguous seq`() = runTest {
        val s = FakeServer().apply { add(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "window.completed", 0, listOf(0, 6_000_000)), ev(4, "analysis.partial")); hidden += 2L; state = "partial" }
        val sync = sync(s)
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.GAP)
        // seq 2 withheld: 3 and 4 wait; nothing after 1 is applied and nothing is skipped.
        assertThat(sync.state.value.lastSeq).isEqualTo(1)
        assertThat(sync.state.value.coveredUs).isEqualTo(0)
        assertThat(sync.state.value.isCatchingUp).isTrue()
        s.hidden.clear()
        s.requests.clear()
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.NEW_EVENTS)
        assertThat(s.requests.first()).isEqualTo(1L)
        assertThat(sync.state.value.lastSeq).isEqualTo(4)
        assertThat(sync.state.value.coveredUs).isEqualTo(6_000_000)
    }

    @Test fun `a gap inside one page is reported as catching up, then as unresolved after retries`() = runTest {
        val s = FakeServer().apply { add(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "analysis.partial")); hidden += 2L; state = "partial" }
        val sync = sync(s)
        sync.syncOnce()
        assertThat(sync.state.value.lastSeq).isEqualTo(1)
        assertThat(sync.state.value.unresolvedGapSeq).isNull()
        repeat(3) { sync.syncOnce() }
        assertThat(sync.state.value.isCatchingUp).isTrue()
        assertThat(sync.state.value.unresolvedGapSeq).isEqualTo(2L)
        s.hidden.clear()
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.NEW_EVENTS)
        assertThat(sync.state.value.unresolvedGapSeq).isNull()
        assertThat(sync.state.value.isCatchingUp).isFalse()
    }

    @Test fun `saved cursor is shown at once as catching up, then only later events are fetched`() = runTest {
        val store = FileCursorStore(tmp.newFolder("cursor"))
        val s = FakeServer().apply { add(ev(1, "analysis.queued"), ev(2, "analysis.started")); state = "processing" }
        sync(s, store).syncOnce()

        // "Restart": new sync, same store; the server has moved on meanwhile.
        s.add(ev(3, "window.completed", 0, listOf(0, 6_000_000)))
        s.state = "partial"
        s.requests.clear()
        s.offline = true
        val again = sync(s, store)
        assertThat(again.syncOnce()).isEqualTo(AnalysisSync.Outcome.ERROR)
        val restored = again.state.value
        assertThat(restored.lastSeq).isEqualTo(2)
        assertThat(restored.state).isEqualTo(AnalysisState.PROCESSING)
        assertThat(restored.isCatchingUp).isTrue()
        assertThat(restored.offline).isTrue()

        s.offline = false
        assertThat(again.syncOnce()).isEqualTo(AnalysisSync.Outcome.NEW_EVENTS)
        assertThat(s.requests.first()).isEqualTo(2L)
        assertThat(again.state.value.isCatchingUp).isFalse()
        assertThat(again.state.value.offline).isFalse()
        assertThat(again.state.value.lastSeq).isEqualTo(3)
    }

    @Test fun `a corrupt or foreign cursor file is ignored`() {
        val dir = tmp.newFolder("bad")
        val store = FileCursorStore(dir)
        dir.resolve("cursor_a_t.json").writeText("{not json")
        assertThat(store.load("a_t")).isNull()
        store.save(CursorStore.Saved("a_t", 2, listOf(ev(1, "analysis.queued")), 1))
        assertThat(store.load("a_t")).isNull() // after_seq 2 but only seq 1 saved
    }

    @Test fun `network loss sets offline and run reconnects with backoff`() = runTest {
        val s = FakeServer().apply { add(ev(1, "analysis.queued")); offline = true }
        val sync = sync(s)
        val job = launch { sync.run() }
        runCurrent()
        assertThat(sync.state.value.offline).isTrue()
        assertThat(sync.state.value.state).isNull()
        s.offline = false
        s.add(ev(2, "analysis.started"))
        s.state = "processing"
        advanceTimeBy(1_100)
        runCurrent()
        assertThat(sync.state.value.offline).isFalse()
        assertThat(sync.state.value.lastSeq).isEqualTo(2)
        // Completion ends the loop by itself.
        s.add(ev(3, "analysis.failed", reason = "dispatch_failed", engine = null))
        s.state = "failed"
        sync.retryNow()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(job.isCompleted).isTrue()
        assertThat(sync.state.value.state).isEqualTo(AnalysisState.FAILED)
        assertThat(sync.state.value.failureReason).isEqualTo("dispatch_failed")
    }

    @Test fun `window failed is a gap with its reason, and coverage counts only completed windows`() = runTest {
        val s = FakeServer().apply {
            add(
                ev(1, "analysis.started"),
                ev(2, "window.completed", 0, listOf(0, 6_000_000), artifact = "art_0"),
                ev(3, "window.failed", 1, listOf(6_000_000, 12_000_000), reason = "decode_error"),
                ev(4, "window.completed", 2, listOf(12_000_000, 18_000_000), artifact = "art_2"),
            )
            state = "partial"
            artifacts["art_0"] = window(0, listOf(0, 6_000_000), listOf(listOf(16, 10.0, 20.0, 0.9, "observed")))
            artifacts["art_2"] = window(2, listOf(12_000_000, 18_000_000), listOf(listOf(12_016, 30.0, 40.0, null, "inferred")))
        }
        val sync = sync(s)
        sync.syncOnce()
        val st = sync.state.value
        assertThat(st.windowsDone).isEqualTo(2)
        assertThat(st.windowsFailed).isEqualTo(1)
        assertThat(st.windowFailures.single().reason).isEqualTo("decode_error")
        assertThat(st.coverageUs).containsExactly(0L..6_000_000L, 12_000_000L..18_000_000L).inOrder()
        assertThat(st.coveredUs).isEqualTo(12_000_000)
        assertThat(st.windowsDownloaded).isEqualTo(2)
        assertThat(sync.ballTrack.value.size).isEqualTo(2)
        assertThat(sync.windowStore().failedWindows).containsExactly(1, "decode_error")
    }

    @Test fun `missing artifact route is reported per window and given up after the policy`() = runTest {
        val s = FakeServer().apply {
            add(ev(1, "window.completed", 0, listOf(0, 6_000_000), artifact = "art_0"), ev(2, "analysis.completed", engine = null))
            state = "completed"
            artifactError = AnalysisApiException(AnalysisApiException.Kind.HTTP, "not_found: route not found", 404, "not_found")
        }
        val sync = sync(s)
        assertThat(sync.syncOnce()).isNotEqualTo(AnalysisSync.Outcome.DONE)
        assertThat(sync.state.value.artifactProblems.single().gaveUp).isFalse()
        sync.syncOnce()
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.DONE)
        val p = sync.state.value.artifactProblems.single()
        assertThat(p.gaveUp).isTrue()
        assertThat(p.reason).contains("route not found")
        assertThat(sync.state.value.windowsDownloaded).isEqualTo(0)
        assertThat(sync.state.value.state).isEqualTo(AnalysisState.COMPLETED)
    }

    @Test fun `same seq with different content is a protocol error, never merged`() = runTest {
        val s = FakeServer().apply { add(ev(1, "analysis.queued"), ev(2, "analysis.started"), ev(3, "analysis.partial")); hidden += 2L; state = "partial" }
        val sync = sync(s)
        sync.syncOnce() // applies 1, holds 3
        s.hidden.clear()
        s.events[3] = ev(3, "analysis.failed", reason = "changed")
        assertThat(sync.syncOnce()).isEqualTo(AnalysisSync.Outcome.ERROR)
        assertThat(sync.state.value.syncError).contains("event stream inconsistent")
        assertThat(sync.state.value.lastSeq).isEqualTo(1)
        assertThat(sync.state.value.state).isEqualTo(AnalysisState.QUEUED)
    }

    @Test fun `engine version comes from the analysis row when events carry none`() = runTest {
        val s = FakeServer().apply {
            add(ev(1, "analysis.queued", engine = null))
            state = "queued"
            analysisRow = AnalysisGetResponse(id, "s_t", "m_t", "queued", "0.4.1", 1, null, "2026-10-11T09:59:58Z", "2026-10-11T10:00:01Z", null)
        }
        val sync = sync(s)
        sync.syncOnce()
        assertThat(sync.state.value.engineVersion).isEqualTo("0.4.1")
        assertThat(sync.state.value.requestedAt).isEqualTo("2026-10-11T09:59:58Z")
        assertThat(sync.matchId).isEqualTo("m_t")
    }

    @Test fun `no clip length means no denominator`() = runTest {
        val s = FakeServer().apply { add(ev(1, "window.completed", 0, listOf(0, 6_000_000))); state = "partial" }
        val sync = sync(s, totalUs = null)
        sync.syncOnce()
        assertThat(sync.state.value.totalUs).isNull()
        assertThat(com.elevate.app.domain.analysis.coverageLabel(sync.state.value.coveredUs, null)).isEqualTo("6.0 s analysed · clip length unknown")
    }
}
