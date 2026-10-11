package com.elevate.app.ui.overview

import com.elevate.app.data.analysis.ArtifactAccess
import com.elevate.app.data.analysis.CacheInfo
import com.elevate.app.data.upload.UploadFailure
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisStage
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.StageState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Upload progress + server analysis state → the analysing checklist, card, notice and pill.
 * Inputs are test vectors shaped like the real types (sizes from the A55 run in PHASE2_STATUS.md).
 */
class AnalysingFlowTest {

    private val mib = 1024L * 1024L
    private val total = 63_698_735L // 60.7 MiB, the h13g_singles upload
    private val started = Screen.Pipeline.Started("a_1", "s_1", "m_1", dispatched = true, state = "queued")

    private fun analysis(
        state: AnalysisState?,
        coveredUs: Long = 0,
        totalUs: Long? = 484_500_000,
        windowsDone: Int = 0,
        downloaded: Int = 0,
        stages: List<String> = emptyList(),
        failure: String? = null,
    ) = AnalysisUiState(
        source = AnalysisSource.Live("a_1"),
        state = state,
        coveredUs = coveredUs,
        totalUs = totalUs,
        windowsDone = windowsDone,
        windowsDownloaded = downloaded,
        stages = stages.mapIndexed { i, t -> AnalysisStage(t, i + 1L, "2026-10-11T10:00:0${i}Z") },
        failureReason = failure,
    )

    private fun uploading(accepted: Long, bps: Double?, eta: Long?, parts: Int = 1) =
        UploadProgress.Uploading(total, accepted, inFlightBytes = 3 * mib, partsDone = parts, partCount = 8, bytesPerSecond = bps, etaSeconds = eta)

    private fun AnalysingFlowState.states() = stages.map { it.state }

    @Test fun `right after the tap the screen shows the first stage before any upload or id exists`() {
        // A stale Completed progress from an earlier upload must not show as sent.
        val f = AnalysingFlow.state(Screen.Pipeline.Uploading, "Identifying the file", UploadProgress.Completed(1), null)
        assertThat(f.states()).containsExactly(StageState.Active, StageState.Pending, StageState.Pending, StageState.Pending).inOrder()
        assertThat(f.stages.map { it.label }).containsExactly("Sending your video", "Queued for the engine", "Tracking the shuttle", "Your match").inOrder()
        assertThat(f.send!!.note).isEqualTo("Identifying the file")
        assertThat(f.send!!.amount).isNull()
        assertThat(f.send!!.fraction).isNull()
        assertThat(f.canCancel).isTrue()
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.NONE)
    }

    @Test fun `sending shows confirmed bytes of the total, measured rate and eta, bar from confirmed only`() {
        val accepted = 12 * mib + mib / 50 // 12.0 MiB confirmed; 3 MiB more written but not confirmed
        val f = AnalysingFlow.state(Screen.Pipeline.Uploading, null, uploading(accepted, 2.4 * mib, 30), null)
        val card = f.send!!
        assertThat(card.amount).isEqualTo("12.0 of 60.7 MiB")
        assertThat(card.fraction!!.toDouble()).isWithin(1e-6).of(accepted.toDouble() / total)
        assertThat(card.rate).isEqualTo("2.4 MiB/s measured")
        assertThat(card.eta).isEqualTo("about 30 s left")
        assertThat(f.stages[0].value).isEqualTo("1/8 parts")
        assertThat(AnalysingFlow.pill(Screen.Pipeline.Uploading, null, uploading(accepted, 2.4 * mib, 30), null)).isEqualTo("Sending · 12.0/60.7 MiB")
    }

    @Test fun `no rate before the first confirmed part means no eta`() {
        val card = AnalysingFlow.state(Screen.Pipeline.Uploading, null, uploading(0, null, null, parts = 0), null).send!!
        assertThat(card.amount).isEqualTo("0.0 of 60.7 MiB")
        assertThat(card.rate).isEqualTo("Measuring speed")
        assertThat(card.eta).isNull()
    }

    @Test fun `offline pause keeps the confirmed bytes and says it resumes`() {
        val f = AnalysingFlow.state(Screen.Pipeline.Uploading, null, UploadProgress.PausedOffline(8 * mib, total), null)
        assertThat(f.send!!.amount).isEqualTo("8.0 of 60.7 MiB")
        assertThat(f.send!!.note).isEqualTo("Offline · resumes on its own")
        assertThat(f.stages[0].value).isEqualTo("paused")
    }

    @Test fun `starting the analysis marks sending done and queue active`() {
        val f = AnalysingFlow.state(Screen.Pipeline.StartingAnalysis("s_1"), null, UploadProgress.Completed(total), null)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Active, StageState.Pending, StageState.Pending).inOrder()
        assertThat(f.stages[0].value).isEqualTo("60.7 MiB")
        assertThat(f.send).isNull()
        assertThat(f.canCancel).isFalse()
    }

    @Test fun `queued from the start answer before any event arrives`() {
        val f = AnalysingFlow.state(started, null, UploadProgress.Completed(total), null)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Active, StageState.Pending, StageState.Pending).inOrder()
        assertThat(f.stages[1].value).isEqualTo("waiting")
        assertThat(AnalysingFlow.pill(started, null, UploadProgress.Idle, null)).isEqualTo("Queued for the engine")
    }

    @Test fun `tracking shows windows and covered seconds of the clip length`() {
        val a = analysis(AnalysisState.PARTIAL, coveredUs = 18_000_000, windowsDone = 3, downloaded = 3, stages = listOf("analysis.queued", "analysis.started", "window.completed"))
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Done, StageState.Active, StageState.Pending).inOrder()
        assertThat(f.stages[2].value).isEqualTo("3 windows · 18.0 of 484.5 s")
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.OPEN_PARTIAL)
        assertThat(f.notice).isNull()
        assertThat(AnalysingFlow.pill(started, null, UploadProgress.Idle, a)).isEqualTo("Analysing · 18/485 s")
    }

    @Test fun `unknown clip length is said, never invented`() {
        val a = analysis(AnalysisState.PROCESSING, coveredUs = 6_000_000, totalUs = null, windowsDone = 1)
        assertThat(AnalysingFlow.tracking(a)).isEqualTo("1 window · 6.0 s · length unknown")
        assertThat(AnalysingFlow.pill(started, null, UploadProgress.Idle, a)).isEqualTo("Analysing · 6 s")
    }

    @Test fun `windows done but the artifact route 404s says detections wait for a server update`() {
        val a = analysis(AnalysisState.PROCESSING, coveredUs = 6_000_000, windowsDone = 1, downloaded = 0)
        val notServed = AnalysingFlow.state(started, null, UploadProgress.Idle, a, ArtifactAccess.NotServed("not_found: route not found", 0))
        assertThat(notServed.notice).isEqualTo(AnalysingFlow.DETECTIONS_NOT_SERVED)
        assertThat(notServed.stages.none { it.state == StageState.Failed }).isTrue()
        assertThat(AnalysingFlow.state(started, null, UploadProgress.Idle, a, ArtifactAccess.Unknown).notice).isNull()
        // Nothing done yet: nothing to say about results.
        assertThat(AnalysingFlow.state(started, null, UploadProgress.Idle, analysis(AnalysisState.PROCESSING), ArtifactAccess.NotServed("x", 0)).notice).isNull()
    }

    @Test fun `completed opens the match`() {
        val a = analysis(AnalysisState.COMPLETED, coveredUs = 484_500_000, windowsDone = 81, downloaded = 81, stages = listOf("analysis.started"))
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Done, StageState.Done, StageState.Done).inOrder()
        assertThat(f.stages[3].value).isEqualTo("ready")
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.OPEN_RESULTS)
    }

    @Test fun `engine failure after it started fails tracking with the server reason`() {
        val a = analysis(AnalysisState.FAILED, stages = listOf("analysis.queued", "analysis.started", "analysis.failed"), failure = "decode_error")
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Done, StageState.Failed, StageState.Pending).inOrder()
        assertThat(f.notice).isEqualTo("Stopped · decode_error")
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.CHECK_AGAIN)
    }

    @Test fun `failure before the engine started fails the queue stage`() {
        val a = analysis(AnalysisState.FAILED, stages = listOf("analysis.queued", "analysis.failed"), failure = "dispatch_failed")
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Failed, StageState.Pending, StageState.Pending).inOrder()
    }

    @Test fun `upload failure keeps the confirmed bytes and offers retry`() {
        val p = Screen.Pipeline.Failed("upload", "part_failed: part 3 ran out of attempts")
        val up = UploadProgress.Failed(UploadFailure("part_failed", "part 3 ran out of attempts", true, 3), 16 * mib, total)
        val f = AnalysingFlow.state(p, null, up, null)
        assertThat(f.states()).containsExactly(StageState.Failed, StageState.Pending, StageState.Pending, StageState.Pending).inOrder()
        assertThat(f.send!!.amount).isEqualTo("16.0 of 60.7 MiB")
        assertThat(f.send!!.note).isEqualTo("part_failed: part 3 ran out of attempts")
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.RETRY_UPLOAD)
        assertThat(AnalysingFlow.pill(p, null, up, null)).isEqualTo("Upload stopped · retry")
    }

    @Test fun `a failed start after a finished upload keeps sending done`() {
        val p = Screen.Pipeline.Failed("analysis", "engine_unavailable: try later")
        val f = AnalysingFlow.state(p, null, UploadProgress.Completed(total), null)
        assertThat(f.states()).containsExactly(StageState.Done, StageState.Failed, StageState.Pending, StageState.Pending).inOrder()
        assertThat(f.notice).isEqualTo("Could not start the analysis · engine_unavailable: try later")
        assertThat(f.send).isNull()
    }

    @Test fun `idle pipeline has no pill`() {
        assertThat(AnalysingFlow.pill(Screen.Pipeline.Idle, null, UploadProgress.Idle, null)).isNull()
        assertThat(AnalysingFlow.pill(Screen.Pipeline.Uploading, "Preparing", UploadProgress.Idle, null)).isEqualTo("Sending · preparing")
    }

    @Test fun `hero line from the real probe`() {
        assertThat(AnalysingFlow.heroLine("badminton", 484_500, 1920, 1080, 50.0)).isEqualTo("Badminton · 8:04 · 1080p50")
        assertThat(AnalysingFlow.heroLine("table_tennis", 3_725_000, 1080, 1920, null)).isEqualTo("Table tennis · 1:02:05 · 1080p")
        assertThat(AnalysingFlow.heroLine(null, null, null, null, null)).isNull()
    }

    @Test fun `eta wording only from a measured value`() {
        assertThat(AnalysingFlow.eta(null)).isNull()
        assertThat(AnalysingFlow.eta(0)).isEqualTo("about 1 s left")
        assertThat(AnalysingFlow.eta(150)).isEqualTo("about 3 min left")
        assertThat(AnalysingFlow.eta(3_900)).isEqualTo("about 1 h 5 min left")
    }

    @Test fun `empty rallies are one short line`() {
        assertThat(AnalysingFlow.ralliesEmpty(null)).isEqualTo("Rallies appear when the engine finishes")
        assertThat(AnalysingFlow.ralliesEmpty(analysis(AnalysisState.COMPLETED))).isEqualTo("Rallies appear when the match record is served")
    }

    private val utc = java.time.ZoneOffset.UTC
    private val cached = CacheInfo(fromCache = true, cachedAtMs = 1_760_000_000_000, windowsCached = 2)

    @Test fun `a finished match restored from the phone says analysed earlier with engine and the completion time`() {
        val a = analysis(AnalysisState.COMPLETED, stages = listOf("analysis.started", "analysis.completed")).copy(engineVersion = "0.3.0")
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a, ArtifactAccess.Unknown, cached, utc)
        assertThat(f.notice).isEqualTo("Analysed earlier · engine 0.3.0 · 11 Oct 2026 10:00")
        // Offline too: the same line replaces "Offline · showing the last update".
        val off = AnalysingFlow.state(started, null, UploadProgress.Idle, a.copy(offline = true), ArtifactAccess.Unknown, cached, utc)
        assertThat(off.notice).isEqualTo("Analysed earlier · engine 0.3.0 · 11 Oct 2026 10:00")
    }

    @Test fun `without a completion event the cache time is used, unknown engine left out`() {
        val a = analysis(AnalysisState.COMPLETED).copy(offline = true)
        assertThat(AnalysingFlow.analysedEarlier(a, cached, utc)).isEqualTo("Analysed earlier · 9 Oct 2025 08:53")
    }

    @Test fun `analysed earlier is never said of fresh data or an unfinished analysis`() {
        val done = analysis(AnalysisState.COMPLETED).copy(engineVersion = "0.3.0")
        assertThat(AnalysingFlow.analysedEarlier(done, cached.copy(fromCache = false), utc)).isNull()
        assertThat(AnalysingFlow.analysedEarlier(done, null, utc)).isNull()
        val running = analysis(AnalysisState.PROCESSING, coveredUs = 6_000_000, windowsDone = 1).copy(offline = true)
        assertThat(AnalysingFlow.analysedEarlier(running, cached, utc)).isNull()
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, running, ArtifactAccess.Unknown, cached, utc)
        assertThat(f.notice).isEqualTo("Offline · showing the last update")
        // The old signature still works and never says it.
        assertThat(AnalysingFlow.state(started, null, UploadProgress.Idle, done).notice).isNull()
    }

    @Test fun `a failed cancel says so and retry cancels, never upload stopped`() {
        val p = Screen.Pipeline.Failed(AnalysingFlow.CANCEL_STAGE, "no network (host not resolved)")
        val f = AnalysingFlow.state(p, null, UploadProgress.Idle, null)
        assertThat(f.stages.first().value).isEqualTo("cancel failed")
        assertThat(f.send!!.note).isEqualTo("Could not cancel the upload · Try again cancels it · no network (host not resolved)")
        assertThat(AnalysingFlow.pill(p, null, UploadProgress.Idle, null)).isEqualTo("Cancel failed · tap to cancel again")
    }
}
