package com.elevate.app.ui.overview

import com.elevate.app.data.analysis.ArtifactAccess
import com.elevate.app.data.api.WindowEvent
import com.elevate.app.data.api.WindowQuality
import com.elevate.app.data.api.WindowResult
import com.elevate.app.data.upload.UploadProgress
import com.elevate.app.domain.analysis.AnalysisSource
import com.elevate.app.domain.analysis.AnalysisState
import com.elevate.app.domain.analysis.AnalysisUiState
import com.elevate.app.domain.analysis.WindowResultStore
import com.elevate.app.ui.Screen
import com.elevate.app.ui.components.StageState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Overview and analysing screen built from the live windows when `match.v1` is missing. Test
 * vectors shaped like the live run in PHASE2_STATUS.md (5 windows over 25.6 s, 19/0/15/53/14
 * observed rows = 101); positions and hit times are made up for the test, never shown in the app.
 */
class LiveOverviewTest {

    private val totalUs = 25_600_000L
    private val emits = listOf(0L to 6_000L, 6_000L to 12_000L, 12_000L to 18_000L, 18_000L to 24_000L, 24_000L to 25_600L)
    private val perWindow = listOf(19, 0, 15, 53, 14)
    /** 25 fps: 640 frame times over 0–25.56 s. */
    private val frameTimesUs = LongArray(640) { it * 40_000L }

    private fun row(tMs: Long) = JsonArray(listOf(JsonPrimitive(tMs), JsonPrimitive(100.0), JsonPrimitive(100.0), JsonPrimitive(0.9), JsonPrimitive("observed")))
    private fun hit(tMs: Long) = WindowEvent("e_$tMs", "hit", tMs, 10, "ball_1", listOf(100.0, 100.0), listOf("trajectory"), "candidate")

    private val hitsMs = listOf(3_000L, 10_000L, 11_000L, 21_800L, 22_000L, 22_200L, 22_400L, 22_600L, 22_800L)

    private fun store(windows: Int = 5): WindowResultStore {
        val s = WindowResultStore("a_1", 1920, 1080)
        for (i in 0 until windows) {
            val (from, to) = emits[i]
            // Frames strictly inside the window (never on a shared boundary), 40 ms apart.
            val rows = (0 until perWindow[i]).map { k -> row(from + 40 + k * 40L) }
            val hits = hitsMs.filter { it > from && it < to }.map(::hit)
            val w = WindowResult(
                "window_result.v1", "a_1", i, listOf(from * 1000, to * 1000), listOf(from * 1000, to * 1000),
                listOf("tracknet_v3"), rows, emptyList(), emptyList(), hits, WindowQuality(0.1, 0.0, 0.0),
            )
            assertThat(s.put(w, "0.3.0")).isInstanceOf(WindowResultStore.PutResult.Stored::class.java)
        }
        return s
    }

    private fun analysis(state: AnalysisState = AnalysisState.PARTIAL, coverage: List<LongRange> = listOf(0L..totalUs), total: Long? = totalUs) = AnalysisUiState(
        source = AnalysisSource.Live("a_1"),
        state = state,
        coverageUs = coverage,
        coveredUs = AnalysisUiState.coveredUs(coverage),
        totalUs = total,
        windowsDone = 5,
        windowsExpected = 5,
        windowsDownloaded = 5,
        engineVersion = "0.3.0",
    )

    private fun live(s: WindowResultStore, a: AnalysisUiState = analysis(), frames: LongArray? = frameTimesUs): LiveOverviewState {
        val timeline = OverviewDerivation.hitTimeline(s.hits(), s.stored.size, a.windowsDone, a.windowsExpected, s.coverageUs, a.totalUs?.let { it / 1000 })
        return LiveOverview.derive(a, s.track(), s.stored, s.coverageUs, timeline, frames)
    }

    @Test fun `shuttle stat counts observed frames over the real frames in the windows read`() {
        val l = live(store())
        assertThat(l.shuttleFrames).isEqualTo(101)
        assertThat(l.framesInWindows).isEqualTo(640)
        assertThat(l.shuttleLine).isEqualTo("shuttle seen in 101 of 640 frames")
        assertThat(l.windowsComplete).isTrue()
        assertThat(l.coverageLine).isEqualTo("5 of 5 windows read · 25.6 of 25.6 s")
    }

    @Test fun `without probed frame times no frame denominator is invented, seconds analysed instead`() {
        val l = live(store(), frames = null)
        assertThat(l.framesInWindows).isNull()
        assertThat(l.shuttleLine).isEqualTo("shuttle seen in 101 frames · 25.6 s analysed")
        // Times that do not line up with the frames (more seen than real frames): no denominator either.
        assertThat(LiveOverview.shuttleLine(101, 50, 25_600_000)).isEqualTo("shuttle seen in 101 frames · 25.6 s analysed")
    }

    @Test fun `no window read yet means no shuttle line`() {
        val l = live(WindowResultStore("a_1"))
        assertThat(l.shuttleLine).isNull()
        assertThat(l.coverageLine).isEqualTo("0 of 5 windows read · 25.6 of 25.6 s")
        assertThat(l.segments.map { it.status }).containsExactly(WindowSegment.Status.REPORTED)
    }

    @Test fun `rally rows come from hit candidates with clock range, hit count and the timeline id`() {
        val rows = live(store()).rallies
        // 3.0 s is a lone hit: no rally candidate.
        assertThat(rows.map { it.label }).containsExactly(
            "Rally candidate 1 · 0:10.0–0:11.0 · 2 hit candidates",
            "Rally candidate 2 · 0:21.8–0:22.8 · 6 hit candidates",
        ).inOrder()
        assertThat(rows[1].rallyId).isEqualTo("${HIT_RALLY_ID_PREFIX}2")
        assertThat(rows[1].startMs).isEqualTo(21_800)
        assertThat(rows[1].endMs).isEqualTo(22_800)
    }

    @Test fun `per-window strip has one read segment per window with its shuttle frames`() {
        val segs = live(store()).segments
        assertThat(segs.map { it.status }.distinct()).containsExactly(WindowSegment.Status.READ)
        assertThat(segs.map { it.windowSeq }).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(segs.map { it.shuttleFrames }).containsExactly(19, 0, 15, 53, 14).inOrder()
        assertThat(segs.last().endMs).isEqualTo(25_600)
    }

    @Test fun `windows reported but not downloaded show as reported, and the denominator follows what was read`() {
        val l = live(store(windows = 3))
        assertThat(l.segments.filter { it.status == WindowSegment.Status.REPORTED }.map { it.startMs to it.endMs })
            .containsExactly(18_000L to 25_600L)
        assertThat(l.shuttleFrames).isEqualTo(34)
        assertThat(l.framesInWindows).isEqualTo(451) // frames at 0..18.0 s inclusive, 40 ms apart
        assertThat(l.coverageLine).isEqualTo("3 of 5 windows read · 25.6 of 25.6 s")
    }

    @Test fun `pending line says plainly what the windows cannot give`() {
        assertThat(live(store()).pendingLine).isEqualTo("Match summary (score, landings) pending the engine")
        assertThat(live(store(), analysis(coverage = listOf(0L..18_000_000L))).pendingLine)
            .isEqualTo("Engine still analysing · match summary (score, landings) pending the engine")
        assertThat(live(store(), analysis(state = AnalysisState.COMPLETED)).pendingLine)
            .isEqualTo("Match summary (score, landings) not served yet")
    }

    @Test fun `overview derivation carries the live view only without a match record`() {
        val l = live(store())
        val o = OverviewDerivation.derive("clip.webm", match = null, analysis = analysis(), live = l)
        assertThat(o.live).isSameInstanceAs(l)
        assertThat(o.rallies).isInstanceOf(RalliesState.NotAvailable::class.java)
        assertThat(o.capabilities).isEmpty()
    }

    // --- Analysing screen ---

    private val started = Screen.Pipeline.Started("a_1", "s_1", "m_1", dispatched = true, state = "queued")

    @Test fun `every window in marks your match ready from the windows, with the summary pending`() {
        val a = analysis()
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a, ArtifactAccess.Serving)
        assertThat(f.stages.map { it.state }).containsExactly(StageState.Done, StageState.Done, StageState.Done, StageState.Done).inOrder()
        assertThat(f.stages[3].value).isEqualTo("ready · from the windows")
        assertThat(f.stages[2].value).isEqualTo("5 windows · 25.6 of 25.6 s")
        assertThat(f.notice).isEqualTo(AnalysingFlow.SUMMARY_PENDING)
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.OPEN_RESULTS)
        assertThat(AnalysingFlow.pill(started, null, UploadProgress.Idle, a)).isEqualTo("Windows done · summary pending")
    }

    @Test fun `a gap in the windows keeps tracking active and your match next`() {
        val a = analysis(coverage = listOf(0L..12_000_000L, 18_000_000L..totalUs))
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a)
        assertThat(f.stages[2].state).isEqualTo(StageState.Active)
        assertThat(f.stages[3].state).isEqualTo(StageState.Pending)
        assertThat(f.action).isEqualTo(AnalysingFlowState.Action.OPEN_PARTIAL)
        assertThat(f.notice).isNull()
    }

    @Test fun `detections not served still wins the notice line`() {
        val a = analysis().copy(windowsDownloaded = 0)
        val f = AnalysingFlow.state(started, null, UploadProgress.Idle, a, ArtifactAccess.NotServed("404", 0))
        assertThat(f.notice).isEqualTo(AnalysingFlow.DETECTIONS_NOT_SERVED)
    }
}
