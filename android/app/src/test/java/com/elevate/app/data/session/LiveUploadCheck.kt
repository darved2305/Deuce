package com.elevate.app.data.session

import com.elevate.app.data.auth.FileTokenStore
import com.elevate.app.data.auth.SupabaseAuth
import com.elevate.app.data.upload.FilePartSource
import com.elevate.app.data.upload.UploadCoordinator
import com.elevate.app.data.upload.UploadPhase
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.data.upload.UploadStateStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Properties

/**
 * LIVE check against the DEPLOYED API and real R2: ONE real upload of a real clip. Skipped unless
 * the environment has `ELEVATE_LIVE=1` (never part of the normal suite; R2 budget rule).
 *
 *     ELEVATE_LIVE=1 ELEVATE_LIVE_CLIP=<path to eurogames_bern_1.webm> ./gradlew testDebugUnitTest --tests '*LiveUploadCheck*'
 *
 * Reads `android/local.properties` (supabaseUrl, publishableKey, apiBaseUrl); prints only IDs,
 * statuses and byte counts, never tokens or presigned URLs. Runs the production classes
 * (SupabaseAuth, ElevateHttp, SessionClient, UploadCoordinator, UrlConnectionTransport).
 *
 * Source facts are the clip's real ones (android/proof/video-manifest.md + its Matroska header);
 * sha256 and bytes are computed here. `who_is_me` is NOT a user tap: it is the frame centre, set by
 * the check because the contract requires one; this session proves the upload path only.
 */
class LiveUploadCheck {

    @Test fun oneRealUpload() = runBlocking<Unit> {
        assumeTrue("set ELEVATE_LIVE=1 to run the live upload", System.getenv("ELEVATE_LIVE") == "1")
        val androidDir = File(TestRequests.repo, "android")
        val props = Properties().apply { File(androidDir, "local.properties").inputStream().use { load(it) } }
        val supabaseUrl = props.getProperty("elevate.supabaseUrl").orEmpty()
        val key = props.getProperty("elevate.supabasePublishableKey").orEmpty()
        val apiBase = props.getProperty("elevate.apiBaseUrl").orEmpty()
        assumeTrue("local.properties lacks the three elevate.* values", supabaseUrl.isNotBlank() && key.isNotBlank() && apiBase.isNotBlank())

        val clip = File(System.getenv("ELEVATE_LIVE_CLIP") ?: File(androidDir, "test-media/raw/eurogames_bern_1.webm").path)
        assumeTrue("clip not found: ${clip.name}", clip.isFile)
        val bytes = clip.length()
        val sha = MessageDigest.getInstance("SHA-256").let { md ->
            clip.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        println("LIVE clip=${clip.name} bytes=$bytes sha256=$sha")

        val work = Files.createTempDirectory("elevate-live").toFile()
        // Local only: holds this run's anonymous session, to re-read the session if the check fails late.
        println("LIVE workdir=${work.path}")
        val transport = UrlConnectionTransport()
        val auth = SupabaseAuth(supabaseUrl, key, transport, FileTokenStore(File(work, "auth")))
        val sessions = SessionClient(ElevateHttp(apiBase, key, auth, transport))
        val store = UploadStateStore(File(work, "uploads"))
        val coordinator = UploadCoordinator(sessions, UrlConnectionTransport(readTimeoutMs = 120_000), store, { FilePartSource(clip) })

        val request = TestRequests.request(TestRequests.eurogamesSource(bytes, sha, device = "jvm-live-check"))
        coordinator.prepare(request, clip.toURI().toString())

        val seen = Collections.synchronizedList(mutableListOf<UploadProgress>())
        val watcher = launch(Dispatchers.IO) { coordinator.progress.collect { seen += it } }
        val t0 = System.nanoTime()
        val result = coordinator.resume()!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        watcher.cancel()

        println("LIVE user is_anonymous=${auth.currentUser()?.isAnonymous}")
        println("LIVE phase=${result.phase} failure=${result.failure}")
        println("LIVE session_id=${result.sessionId} match_id=${result.matchId} part_size=${result.partSize} parts=${result.plan?.count} max_concurrency=${result.maxConcurrency}")
        println("LIVE etags=${result.etags.size} confirmed_bytes=${result.confirmedBytes} elapsed_ms=$ms")
        val uploading = seen.filterIsInstance<UploadProgress.Uploading>()
        println("LIVE progress_states=${seen.map { it::class.simpleName }.distinct()} uploading_samples=${uploading.size} max_measured_Bps=${uploading.mapNotNull { it.bytesPerSecond }.maxOrNull()}")

        assertThat(result.phase).isEqualTo(UploadPhase.Completed)
        val view = sessions.getSession(result.sessionId!!)
        println("LIVE GET /v1/sessions/{id}: session_id=${view.sessionId} match_id=${view.matchId} mode=${view.mode} upload_mode=${view.uploadMode} created_at=${view.createdAt}")
        println("LIVE GET upload: status=${view.upload?.status} bytes=${view.upload?.bytes} part_size=${view.upload?.partSize} part_count=${view.upload?.partCount} completed_at=${view.upload?.completedAt}")
        println("LIVE GET source: bytes=${view.source.bytes} sha256=${view.source.sha256}")
        assertThat(view.upload!!.status).isEqualTo("completed")
        assertThat(view.upload.bytes).isEqualTo(bytes)
        assertThat(result.confirmedBytes).isEqualTo(bytes)
        work.deleteRecursively()
    }
}
