package com.elevate.app.data.record

import com.elevate.app.data.session.ApiException
import com.elevate.app.data.session.TransportException
import com.elevate.app.data.upload.RetryPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** The segment upload state machine against fakes of the segment routes and R2. */
class SegmentUploaderTest {

    private class FakeApi : SegmentApi {
        val events = mutableListOf<String>()
        val registered = mutableMapOf<Int, SegmentRegisterRequest>()
        var registerFaults = ArrayDeque<Exception>()
        var completeFaults = ArrayDeque<Exception>()
        var urlRequests = mutableMapOf<Int, Int>()
        var expiresAt = "2099-01-01T00:00:00Z"
        var analysisAfterZero = false

        override suspend fun createSession(request: SegmentSessionRequest, idempotencyKey: String) = error("not used")

        override suspend fun register(sessionId: String, request: SegmentRegisterRequest): SegmentRegisterResponse {
            events += "register ${request.seq} final=${request.final}"
            registerFaults.removeFirstOrNull()?.let { throw it }
            val high = registered.keys.maxOrNull() ?: -1
            if (request.seq > high + 1) throw ApiException(409, "segment_gap", "gap")
            registered[request.seq] = request
            urlRequests.merge(request.seq, 1, Int::plus)
            return SegmentRegisterResponse(request.seq, "seg/$sessionId/${request.seq}.mp4", "https://r2.test/${request.seq}?sig=${urlRequests[request.seq]}", expiresAt)
        }

        override suspend fun complete(sessionId: String, seq: Int): SegmentCompleteResponse {
            events += "complete $seq"
            completeFaults.removeFirstOrNull()?.let { throw it }
            val r = registered.getValue(seq)
            return SegmentCompleteResponse(seq, r.ptsStartUs, r.ptsEndUs, r.startsOnSyncSample, r.bytes, r.sha256, r.final, "seg/$sessionId/$seq.mp4", "2026-10-11T00:00:00Z")
        }
    }

    private class FakePutter(private val api: FakeApi) : SegmentPutter {
        val puts = mutableListOf<Pair<Int, String>>()
        var faults = ArrayDeque<Any>() // PutResult or IOException
        val perSeq = mutableMapOf<Int, Int>()
        override suspend fun put(url: String, segment: RecordedSegment): PutResult {
            api.events += "put ${segment.seq}"
            puts += segment.seq to url
            perSeq.merge(segment.seq, 1, Int::plus)
            when (val f = faults.removeFirstOrNull()) {
                null -> return PutResult.Ok
                is IOException -> throw f
                is PutResult -> return f
                else -> error("bad fault")
            }
        }
    }

    private val zeroJitter = object : Random() { override fun nextBits(bitCount: Int) = 0 }
    private val sleeps = mutableListOf<Long>()
    private val api = FakeApi()
    private val putter = FakePutter(api)
    private var analysisStarts = 0

    private fun seg(seq: Int) = RecordedSegment(
        seq, File("seg$seq.mp4"), bytes = 1_000L + seq, sha256 = "a".repeat(64),
        ptsStartUs = seq * 6_000_000L, ptsEndUs = (seq + 1) * 6_000_000L,
    )

    private fun uploader(
        scope: TestScope,
        allowed: () -> Boolean = { true },
        concurrency: Int = 2,
        startAnalysis: suspend () -> Unit = { analysisStarts++; api.events += "analysis" },
    ) = SegmentUploader(
        api, putter, "s_abc", concurrency, scope, networkAllowed = allowed, startAnalysis = startAnalysis,
        retry = RetryPolicy(random = zeroJitter), sleep = { sleeps += it }, nowMs = { 0L },
    )

    @Test fun `register upload complete and final run in order, with analysis after piece 0`() = runTest {
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onSegmentRecorded(seg(1))
        up.onSegmentRecorded(seg(2))
        up.onStopped()
        up.join()

        assertThat(api.registered.keys.toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(api.registered.getValue(0).final).isFalse()
        assertThat(api.registered.getValue(1).final).isFalse()
        assertThat(api.registered.getValue(2).final).isTrue()
        val s = up.status.value
        assertThat(s.sent).isEqualTo(3)
        assertThat(s.recorded).isEqualTo(3)
        assertThat(s.analysisStarted).isTrue()
        assertThat(s.finalRegistered).isTrue()
        assertThat(s.finished).isTrue()
        assertThat(s.failure).isNull()
        assertThat(analysisStarts).isEqualTo(1)
        // The analysis call comes after piece 0 is stored, and each piece is put before it is completed.
        assertThat(api.events.indexOf("analysis")).isGreaterThan(api.events.indexOf("complete 0"))
        for (i in 0..2) assertThat(api.events.indexOf("put $i")).isLessThan(api.events.indexOf("complete $i"))
        assertThat(api.events.indexOf("register 0 final=false")).isLessThan(api.events.indexOf("put 0"))
    }

    @Test fun `analysis starts after piece 0 while later pieces are still being recorded`() = runTest {
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onSegmentRecorded(seg(1)) // releases piece 0
        testScheduler.advanceUntilIdle()
        assertThat(up.status.value.analysisStarted).isTrue()
        assertThat(up.status.value.finished).isFalse()
        assertThat(api.registered.keys).containsExactly(0)
        up.onStopped()
        up.join()
        assertThat(up.status.value.finished).isTrue()
    }

    @Test fun `the held piece is final even when the recording is one piece`() = runTest {
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        testScheduler.advanceUntilIdle()
        assertThat(api.registered).isEmpty() // not registered yet: it may or may not be the last
        up.onStopped()
        up.join()
        assertThat(api.registered.getValue(0).final).isTrue()
        assertThat(up.status.value.analysisStarted).isTrue()
    }

    @Test fun `registration is strictly in order even with two pieces in flight`() = runTest {
        val up = uploader(this, concurrency = 2)
        up.start()
        (0..5).forEach { up.onSegmentRecorded(seg(it)) }
        up.onStopped()
        up.join()
        val registers = api.events.filter { it.startsWith("register") }.map { it.substringAfter(' ').substringBefore(' ').toInt() }
        assertThat(registers).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
        assertThat(up.status.value.sent).isEqualTo(6)
    }

    @Test fun `a failed put is retried on the same URL without asking for a new one`() = runTest {
        putter.faults += PutResult.Rejected(503)
        putter.faults += IOException("reset")
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(putter.perSeq[0]).isEqualTo(3)
        assertThat(putter.puts.map { it.second }.toSet()).hasSize(1)
        assertThat(api.urlRequests[0]).isEqualTo(1)
        assertThat(up.status.value.sent).isEqualTo(1)
        // Backoff doubles: 1 s then 2 s (equal jitter with zero random keeps the fixed half).
        assertThat(sleeps).containsExactly(500L, 1_000L).inOrder()
    }

    @Test fun `a piece gets at most 4 tries and then waits for a retry`() = runTest {
        repeat(10) { putter.faults += PutResult.Rejected(500) }
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(putter.perSeq[0]).isEqualTo(4)
        val f = up.status.value.failure
        assertThat(f).isNotNull()
        assertThat(f!!.code).isEqualTo("part_failed")
        assertThat(f.retryable).isTrue()
        assertThat(up.status.value.finished).isFalse()
        assertThat(up.status.value.analysisStarted).isFalse()
    }

    @Test fun `resume sends a stuck piece again and finishes`() = runTest {
        repeat(4) { putter.faults += PutResult.Rejected(500) }
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(up.status.value.failure).isNotNull()

        up.resume()
        up.join()
        assertThat(up.status.value.failure).isNull()
        assertThat(up.status.value.sent).isEqualTo(1)
        assertThat(up.status.value.finished).isTrue()
        assertThat(api.urlRequests[0]).isEqualTo(2) // idempotent re-register for a fresh link
    }

    @Test fun `a refused link asks for a new one, at most 3 links per piece`() = runTest {
        repeat(5) { putter.faults += PutResult.Rejected(403) }
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(api.urlRequests[0]).isAtMost(SegmentUploader.MAX_URL_REQUESTS)
        assertThat(up.status.value.failure).isNotNull()
        assertThat(up.status.value.sent).isEqualTo(0)
    }

    @Test fun `one 403 re-registers once and the piece still lands`() = runTest {
        putter.faults += PutResult.Rejected(403)
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(api.urlRequests[0]).isEqualTo(2)
        assertThat(putter.puts.map { it.second }.toSet()).hasSize(2)
        assertThat(up.status.value.sent).isEqualTo(1)
    }

    @Test fun `an expired link is replaced once before it is used`() = runTest {
        api.expiresAt = "1970-01-01T00:00:30Z" // within the 60 s margin of now = 0
        val up = SegmentUploader(
            api, putter, "s_abc", 2, this, startAnalysis = { analysisStarts++ },
            retry = RetryPolicy(random = zeroJitter), sleep = { sleeps += it }, nowMs = { 0L },
        )
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        // The first link is already stale, so one fresh link is asked for and then used as issued.
        assertThat(api.urlRequests[0]).isEqualTo(2)
        assertThat(up.status.value.sent).isEqualTo(1)
    }

    @Test fun `going offline pauses without using attempts`() = runTest {
        var online = false
        var polls = 0
        val up = SegmentUploader(
            api, putter, "s_abc", 2, this,
            networkAllowed = { if (++polls > 5) online = true; online },
            startAnalysis = { analysisStarts++ },
            retry = RetryPolicy(random = zeroJitter), sleep = { sleeps += it }, nowMs = { 0L },
        )
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(up.status.value.sent).isEqualTo(1)
        assertThat(up.status.value.waiting).isFalse()
        assertThat(sleeps.filter { it == SegmentUploader.NETWORK_POLL_MS }).isNotEmpty()
        assertThat(putter.perSeq[0]).isEqualTo(1)
    }

    @Test fun `a dropped connection mid-upload is not counted as a failed try`() = runTest {
        repeat(6) { putter.faults += TransportException("offline", offline = true) }
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(up.status.value.failure).isNull()
        assertThat(putter.perSeq[0]).isEqualTo(7)
        assertThat(up.status.value.sent).isEqualTo(1)
    }

    @Test fun `complete answering 409 sends the object again`() = runTest {
        api.completeFaults += ApiException(409, "conflict", "uploaded segment is missing")
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(putter.perSeq[0]).isEqualTo(2)
        assertThat(up.status.value.sent).isEqualTo(1)
    }

    @Test fun `a server error on register is retried then succeeds`() = runTest {
        api.registerFaults += ApiException(503, "internal", "boom")
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        assertThat(up.status.value.sent).isEqualTo(1)
        assertThat(api.events.count { it.startsWith("register 0") }).isEqualTo(2)
    }

    @Test fun `a budget refusal halts without retry loops`() = runTest {
        api.registerFaults += ApiException(429, "storage_budget_reached", "budget", retryAfterS = 86400.0)
        val up = uploader(this)
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onStopped()
        up.join()
        val f = up.status.value.failure
        assertThat(f?.code).isEqualTo("storage_budget_reached")
        assertThat(f?.message).contains("saved on this phone")
        assertThat(api.events.count { it.startsWith("register") }).isEqualTo(1)
        assertThat(putter.puts).isEmpty()
    }

    @Test fun `an analysis start that fails does not stop uploads and is retried at the end`() = runTest {
        var calls = 0
        val up = uploader(this) {
            calls++
            if (calls <= 4) throw ApiException(503, "internal", "down")
        }
        up.start()
        up.onSegmentRecorded(seg(0))
        up.onSegmentRecorded(seg(1))
        up.onStopped()
        up.join()
        assertThat(up.status.value.sent).isEqualTo(2)
        assertThat(up.status.value.failure).isNull()
        assertThat(up.status.value.analysisStarted).isTrue()
        assertThat(calls).isEqualTo(5)
        assertThat(up.status.value.finished).isTrue()
    }

    @Test fun `concurrency never passes the server limit or 2`() = runTest {
        var inFlight = 0
        var peak = 0
        val slow = object : SegmentPutter {
            override suspend fun put(url: String, segment: RecordedSegment): PutResult {
                inFlight++; peak = maxOf(peak, inFlight)
                kotlinx.coroutines.yield()
                inFlight--
                return PutResult.Ok
            }
        }
        val up = SegmentUploader(api, slow, "s_abc", 9, this, startAnalysis = {}, retry = RetryPolicy(random = zeroJitter), sleep = {}, nowMs = { 0L })
        up.start()
        (0..7).forEach { up.onSegmentRecorded(seg(it)) }
        up.onStopped()
        up.join()
        assertThat(peak).isAtMost(2)
        assertThat(up.status.value.sent).isEqualTo(8)
    }
}
