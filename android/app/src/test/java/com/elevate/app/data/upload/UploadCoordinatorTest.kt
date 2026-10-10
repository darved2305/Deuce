package com.elevate.app.data.upload

import com.elevate.app.data.session.ContractFakeServer
import com.elevate.app.data.session.ContractFakeServer.Fault
import com.elevate.app.data.session.SessionClient
import com.elevate.app.data.session.TestRequests
import com.elevate.app.data.session.clientFor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import kotlin.random.Random

/**
 * The multipart upload end to end against [ContractFakeServer] (a test fake mirroring Person 3's
 * routes and R2). Parts are 1,000 bytes here so a file of a few KB has several parts.
 */
class UploadCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_760_000_000_000L
    private val sleeps = Collections.synchronizedList(mutableListOf<Long>())
    private val fake = ContractFakeServer(nowMs = { now }, partSize = 1000)
    private val api: SessionClient = clientFor(fake, nowS = { now / 1000 }, nowMs = { now }).first
    private val zeroJitter = object : Random() { override fun nextBits(bitCount: Int) = 0 }
    private val stateDir by lazy { tmp.newFolder("uploads") }

    private fun file(size: Int): File = tmp.newFile().apply { writeBytes(ByteArray(size) { (it * 31 % 256).toByte() }) }

    private fun coordinator(
        f: File,
        onSleep: (Long) -> Unit = {},
        source: PartSource = FilePartSource(f),
    ) = UploadCoordinator(
        api = api,
        partTransport = fake,
        store = UploadStateStore(stateDir),
        sourceFor = { source },
        policy = RetryPolicy(random = zeroJitter),
        nowMs = { now },
        sleep = { sleeps += it; onSleep(it) },
        io = Dispatchers.IO,
        newId = { "idem-${f.name}" },
    )

    private fun prepare(c: UploadCoordinator, f: File, staged: String? = null): UploadState {
        val bytes = f.readBytes()
        return c.prepare(TestRequests.request(TestRequests.eurogamesSource(bytes.size.toLong(), TestRequests.sha256(bytes))), "content://media/picker/1", staged)
    }

    private fun completeBody(): Map<Int, String> {
        val r = fake.requests.last { it.url.endsWith("/upload/complete") }
        val out = ByteArrayOutputStream().also { r.body!!.writeTo(it) }
        return Json.parseToJsonElement(out.toString()).jsonObject["parts"]!!.jsonArray.associate {
            it.jsonObject["n"]!!.jsonPrimitive.int to it.jsonObject["etag"]!!.jsonPrimitive.content
        }
    }

    private fun putsFor(n: Int) = fake.puts().count { it.url.contains("partNumber=$n&") }

    @Test fun `uploads every part, completes in order with the ETags verbatim, server confirms bytes`() = runBlocking<Unit> {
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.phase).isEqualTo(UploadPhase.Completed)
        assertThat(s.confirmedBytes).isEqualTo(2500)
        assertThat(c.progress.value).isEqualTo(UploadProgress.Completed(2500))
        assertThat(fake.assembled(s.sessionId!!)).isEqualTo(f.readBytes())
        // Content-Length per part: 1000, 1000, 500 (short final part).
        assertThat(fake.puts().map { it.body!!.contentLength }.sorted()).containsExactly(500L, 1000L, 1000L).inOrder()
        // ETags exactly as R2 returned them (quoted MD5, not a sha256), in part order.
        val sent = completeBody()
        assertThat(sent.keys.toList()).containsExactly(1, 2, 3).inOrder()
        assertThat(sent).isEqualTo(fake.uploads.getValue(s.sessionId).etags.toMap())
        assertThat(sent.values.all { it.matches(Regex("^\"[0-9a-f]{32}\"$")) }).isTrue()
        // No auth headers on presigned R2 PUTs.
        assertThat(fake.puts().all { "Authorization" !in it.headers && "apikey" !in it.headers }).isTrue()
        assertThat(UploadStateStore(stateDir).load()!!.phase).isEqualTo(UploadPhase.Completed)
    }

    @Test fun `exact multiple of the part size`() = runBlocking<Unit> {
        val f = file(3000)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.confirmedBytes).isEqualTo(3000)
        assertThat(fake.puts().map { it.body!!.contentLength }).containsExactly(1000L, 1000L, 1000L)
    }

    @Test fun `at most 2 parts in flight`() = runBlocking<Unit> {
        fake.putDelayMs = 40
        val f = file(6000)
        val c = coordinator(f)
        prepare(c, f)
        assertThat(c.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(fake.maxConcurrentPuts.get()).isEqualTo(2)
    }

    @Test fun `size limits - 0 bytes and over 300 MiB are refused before any network call`() {
        val c = coordinator(file(10))
        assertThrows(IllegalArgumentException::class.java) {
            c.prepare(TestRequests.request(TestRequests.eurogamesSource(300L * 1024 * 1024 + 1, "a".repeat(64))), "content://x")
        }
        assertThrows(IllegalArgumentException::class.java) { TestRequests.eurogamesSource(0, "a".repeat(64)) }
        assertThat(fake.requests).isEmpty()
    }

    @Test fun `500 and 503 on a part are retried with backoff and succeed`() = runBlocking<Unit> {
        fake.fault(2, Fault.Status(500), Fault.Status(503))
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        assertThat(c.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(putsFor(2)).isEqualTo(3)
        assertThat(sleeps).containsExactly(500L, 1000L).inOrder()
    }

    @Test fun `a part gets at most 4 attempts (G11), then the upload stops resumable`() = runBlocking<Unit> {
        fake.fault(2, Fault.Status(500), Fault.Status(500), Fault.Status(500), Fault.Status(500), Fault.Status(500))
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.phase).isEqualTo(UploadPhase.Failed)
        assertThat(s.failure!!.code).isEqualTo("part_failed")
        assertThat(s.failure.part).isEqualTo(2)
        assertThat(s.failure.retryable).isTrue()
        assertThat(putsFor(2)).isEqualTo(4)
        assertThat(sleeps).containsExactly(500L, 1000L, 2000L).inOrder()
        assertThat(fake.calls("/upload/complete")).isEqualTo(0)
        val p = c.progress.value as UploadProgress.Failed
        // Only accepted parts count: part 2's written bytes never do. Part 1 was in flight beside
        // part 2, so it always finishes and is kept; part 3 is kept only if its worker took it
        // before part 2 gave up (both are correct, so the exact total is not asserted).
        assertThat(s.etags.keys).contains(1)
        assertThat(s.etags.keys).doesNotContain(2)
        assertThat(p.acceptedBytes).isEqualTo(s.acceptedBytes)
        assertThat(p.acceptedBytes).isAnyOf(1000L, 1500L)
    }

    @Test fun `a part in flight when another part fails for good is still saved`() = runBlocking<Unit> {
        // Part 1's body is held until part 2 has used its 4th and last attempt, then sent: R2 accepts
        // it after the run has already decided to stop. Its ETag must be saved, or a resume would
        // upload bytes R2 already has (and the saved state would depend on timing).
        repeat(4) { fake.fault(2, Fault.Status(500)) }
        val f = file(2500)
        val part2LastTry = java.util.concurrent.CountDownLatch(1)
        var part2Opens = 0
        val source = object : PartSource {
            override fun open(offset: Long, length: Long): java.io.InputStream {
                if (offset == 1000L && ++part2Opens == 4) part2LastTry.countDown()
                if (offset == 0L) {
                    part2LastTry.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(300) // part 2's 4th PUT fails and the run decides to stop meanwhile
                }
                return FilePartSource(f).open(offset, length)
            }
        }
        val c = coordinator(f, source = source)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.phase).isEqualTo(UploadPhase.Failed)
        assertThat(s.failure!!.part).isEqualTo(2)
        assertThat(s.etags.keys).contains(1)
        assertThat(s.etags.keys).doesNotContain(3) // no new part starts once the run stops
    }

    @Test fun `403 on a PUT re-requests that part's URL immediately`() = runBlocking<Unit> {
        fake.fault(1, Fault.Status(403, "<Error><Code>AccessDenied</Code></Error>"))
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        assertThat(c.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        val urlRequestsForPart1 = fake.requests.count { it.url.endsWith("/upload/parts") && ByteArrayOutputStream().also { o -> it.body!!.writeTo(o) }.toString() == """{"part_numbers":[1]}""" }
        assertThat(urlRequestsForPart1).isEqualTo(2)
        assertThat(sleeps).isEmpty()
        val urls = fake.puts().filter { it.url.contains("partNumber=1&") }.map { it.url }
        assertThat(urls).hasSize(2)
    }

    @Test fun `a cached URL is reused before expires_at and re-requested once it is near expiry`() = runBlocking<Unit> {
        // 500 then success: the retry reuses the same URL (no new URL spent, G4).
        fake.fault(1, Fault.Status(500))
        val f = file(500)
        val c = coordinator(f)
        prepare(c, f)
        c.resume()
        assertThat(fake.calls("/upload/parts")).isEqualTo(1)
        assertThat(fake.puts().map { it.url }.distinct()).hasSize(1)

        // 500, then 10 minutes pass during the backoff: the expired URL is not used again.
        val g = file(500)
        val c2 = UploadCoordinator(api, fake, UploadStateStore(tmp.newFolder()), { FilePartSource(g) }, RetryPolicy(random = zeroJitter), { now }, { now += 600_000 }, Dispatchers.IO, { "k2" })
        fake.fault(1, Fault.Status(500))
        val bytes = g.readBytes()
        c2.prepare(TestRequests.request(TestRequests.eurogamesSource(500, TestRequests.sha256(bytes))), "content://y")
        val before = fake.calls("/upload/parts")
        assertThat(c2.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(fake.calls("/upload/parts") - before).isEqualTo(2)
    }

    @Test fun `offline - pauses after its attempts, then resumes only the missing parts`() = runBlocking<Unit> {
        repeat(4) { fake.fault(3, Fault.Throw(offline = true)) }
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.failure!!.code).isEqualTo(MultipartUploader.OFFLINE)
        assertThat(c.progress.value).isEqualTo(UploadProgress.PausedOffline(2000, 2500))
        val putsBefore = fake.puts().size
        val again = c.resume()!!
        assertThat(again.phase).isEqualTo(UploadPhase.Completed)
        assertThat(fake.puts().drop(putsBefore).map { it.url.substringAfter("partNumber=").substringBefore('&') }).containsExactly("3")
    }

    @Test fun `resume after process death - a new instance reads the saved state and sends only missing parts`() = runBlocking<Unit> {
        // The LAST part (4 of 4) runs out of attempts. With 2 workers it is only taken after two other
        // parts were confirmed, so the saved state always holds >= 2 ETags. (Failing part 2 raced with
        // part 1: when the run stops, part 1 may or may not have been confirmed; CI run 38091378043.)
        repeat(4) { fake.fault(4, Fault.Status(500)) }
        val f = file(3500)
        val first = coordinator(f)
        prepare(first, f)
        val failed = first.resume()!!
        assertThat(failed.phase).isEqualTo(UploadPhase.Failed)
        assertThat(failed.etags.keys).doesNotContain(4)
        assertThat(failed.etags.size).isAtLeast(2)
        // In-flight parts finish on a failed run, so nothing R2 accepted is forgotten.
        val missing = 4 - failed.etags.size
        val putsBefore = fake.puts().size
        // "Process death": nothing survives but the files in stateDir.
        val second = coordinator(f)
        assertThat(second.progress.value).isInstanceOf(UploadProgress.Failed::class.java)
        val done = second.resume()!!
        assertThat(done.phase).isEqualTo(UploadPhase.Completed)
        assertThat(done.sessionId).isEqualTo(failed.sessionId)
        assertThat(fake.puts().drop(putsBefore)).hasSize(missing)
        assertThat(fake.assembled(done.sessionId!!)).isEqualTo(f.readBytes())
        assertThat(fake.uploads).hasSize(1)
    }

    @Test fun `process death after the server created the session - the same Idempotency-Key replays it`() = runBlocking<Unit> {
        val f = file(1500)
        val c = coordinator(f)
        val saved = prepare(c, f)
        // The create reached the server but the app died before saving the answer.
        val lost = api.createSession(TestRequests.request(TestRequests.eurogamesSource(1500, saved.sha256)), saved.idempotencyKey)
        val s = coordinator(f).resume()!!
        assertThat(s.sessionId).isEqualTo(lost.sessionId)
        assertThat(fake.uploads).hasSize(1)
        assertThat(s.phase).isEqualTo(UploadPhase.Completed)
    }

    @Test fun `duplicate complete is harmless`() = runBlocking<Unit> {
        val f = file(1500)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        // Died after the server completed but before the state was saved: phase still Completing.
        UploadStateStore(stateDir).save(s.copy(phase = UploadPhase.Completing, confirmedBytes = null))
        val again = coordinator(f).resume()!!
        assertThat(again.phase).isEqualTo(UploadPhase.Completed)
        assertThat(again.confirmedBytes).isEqualTo(1500)
        assertThat(fake.calls("/upload/complete")).isEqualTo(2)
        assertThat(fake.puts()).hasSize(2)
    }

    @Test fun `upload_parts_invalid with missing parts re-sends those parts once`() = runBlocking<Unit> {
        fake.apiFault(
            "/upload/complete",
            Fault.Status(409, """{"error":{"code":"upload_parts_invalid","message":"parts must list every part 1..N exactly once","details":{"expected_parts":2,"missing":[2]}}}"""),
        )
        val f = file(1500)
        val c = coordinator(f)
        prepare(c, f)
        assertThat(c.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(putsFor(2)).isEqualTo(2)
        assertThat(fake.calls("/upload/complete")).isEqualTo(2)
    }

    @Test fun `a connection dropped while fetching a part URL is retried, not fatal`() = runBlocking<Unit> {
        // Seen on the A55 on LTE: SSLHandshakeException on POST upload/parts ended the whole upload.
        fake.apiFault("/upload/parts", Fault.Throw(offline = false))
        val f = file(500)
        val c = coordinator(f)
        prepare(c, f)
        assertThat(c.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(fake.calls("/upload/parts")).isAtLeast(2)
    }

    @Test fun `too_many_part_requests stops the upload without retrying`() = runBlocking<Unit> {
        fake.apiFault("/upload/parts", Fault.Status(429, """{"error":{"code":"too_many_part_requests","message":"spent"}}"""))
        val f = file(500)
        val c = coordinator(f)
        prepare(c, f)
        val s = c.resume()!!
        assertThat(s.failure!!.code).isEqualTo("too_many_part_requests")
        assertThat(s.failure.retryable).isFalse()
        assertThat(fake.puts()).isEmpty()
    }

    @Test fun `401 during the upload refreshes and continues`() = runBlocking<Unit> {
        val f = file(2500)
        val c = coordinator(f)
        prepare(c, f)
        var expired = false
        fake.putDelayMs = 0
        val source = object : PartSource {
            override fun open(offset: Long, length: Long) = FilePartSource(f).open(offset, length).also {
                if (!expired) { expired = true; fake.expireTokens() }
            }
        }
        val c2 = coordinator(f, source = source)
        assertThat(c2.resume()!!.phase).isEqualTo(UploadPhase.Completed)
        assertThat(fake.refreshes.get()).isAtLeast(1)
        assertThat(c.current()!!.phase).isEqualTo(UploadPhase.Completed)
    }

    @Test fun `cancel aborts on the server, deletes state and the staging copy`() = runBlocking<Unit> {
        repeat(4) { fake.fault(2, Fault.Status(500)) }
        val f = file(2500)
        val staged = tmp.newFile("staged.mp4").apply { writeBytes(f.readBytes()) }
        val c = coordinator(f)
        prepare(c, f, staged.path)
        val s = c.resume()!!
        c.cancel()
        assertThat(fake.uploads.getValue(s.sessionId!!).status).isEqualTo("aborted")
        assertThat(UploadStateStore(stateDir).load()).isNull()
        assertThat(staged.exists()).isFalse()
        assertThat(c.progress.value).isEqualTo(UploadProgress.Cancelled)
        // Aborting twice is fine (the route is idempotent).
        assertThat(api.abort(s.sessionId).status).isEqualTo("aborted")
    }

    @Test fun `cancel while parts are in flight stops the run before aborting`() = runBlocking<Unit> {
        fake.putDelayMs = 30
        val f = file(9000)
        val c = coordinator(f)
        prepare(c, f)
        val run = launch(Dispatchers.IO) { c.resume() }
        withTimeout(5_000) { while (fake.puts().isEmpty()) kotlinx.coroutines.delay(5) }
        c.cancel()
        run.join()
        val u = fake.uploads.values.single()
        assertThat(u.status).isEqualTo("aborted")
        assertThat(UploadStateStore(stateDir).load()).isNull()
        assertThat(fake.calls("/upload/complete")).isEqualTo(0)
    }

    @Test fun `progress counts accepted bytes, in-flight bytes separately, and measured rate only`() = runBlocking<Unit> {
        val seen = Collections.synchronizedList(mutableListOf<UploadProgress>())
        fake.putDelayMs = 20
        val f = file(4500)
        val c = coordinator(f)
        prepare(c, f)
        val watcher = launch(Dispatchers.IO) { c.progress.collect { seen += it } }
        c.resume()
        watcher.cancel()
        val uploading = seen.filterIsInstance<UploadProgress.Uploading>()
        assertThat(uploading).isNotEmpty()
        val possibleAccepted = setOf(0L, 500L) + (0..4).map { it * 1000L } + (0..4).map { it * 1000L + 500 }
        for (p in uploading) {
            assertThat(p.acceptedBytes).isIn(possibleAccepted)
            assertThat(p.inFlightBytes).isAtMost(2 * 1000L)
            assertThat(p.totalBytes).isEqualTo(4500)
            // The clock never moved in this test: no rate, so no ETA (nothing invented).
            assertThat(p.bytesPerSecond).isNull()
            assertThat(p.etaSeconds).isNull()
        }
        assertThat(uploading.map { it.acceptedBytes }).isInOrder()
        assertThat(c.progress.value).isEqualTo(UploadProgress.Completed(4500))
    }

    @Test fun `ETA comes from server-confirmed bytes only`() = runBlocking<Unit> {
        val f = file(4000)
        var t = 0L
        val tick = object : PartSource {
            override fun open(offset: Long, length: Long) = FilePartSource(f).open(offset, length).also { t += 1000 }
        }
        val c = UploadCoordinator(api, fake, UploadStateStore(tmp.newFolder()), { tick }, RetryPolicy(random = zeroJitter), { t }, {}, Dispatchers.IO, { "k3" })
        val seen = Collections.synchronizedList(mutableListOf<UploadProgress>())
        val b = f.readBytes()
        c.prepare(TestRequests.request(TestRequests.eurogamesSource(4000, TestRequests.sha256(b))), "content://z")
        val watcher = launch(Dispatchers.IO) { c.progress.collect { seen += it } }
        c.resume()
        watcher.cancel()
        val uploading = seen.filterIsInstance<UploadProgress.Uploading>()
        // Bytes sitting in a socket buffer are not progress: no rate until a part is confirmed.
        assertThat(uploading.filter { it.partsDone == 0 }.all { it.bytesPerSecond == null && it.etaSeconds == null }).isTrue()
        val rated = uploading.filter { it.bytesPerSecond != null }
        assertThat(rated).isNotEmpty()
        for (p in rated) {
            val remaining = p.totalBytes - p.acceptedBytes
            assertThat(p.etaSeconds).isEqualTo(Math.ceil(remaining / p.bytesPerSecond!!).toLong())
        }
    }
}
