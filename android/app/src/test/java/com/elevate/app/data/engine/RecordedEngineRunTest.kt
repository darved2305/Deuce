package com.elevate.app.data.engine

import com.elevate.app.domain.analysis.WindowResultStore
import com.elevate.app.domain.model.ClipIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Person 1's real recorded run (`engine/eval/runs/h13g_w1`): counts must match the file exactly. */
class RecordedEngineRunTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/contracts/schemas").isDirectory }
    private val runDir = File(repo, "engine/eval/runs/h13g_w1")
    private val clip = ClipIdentity("h13g_singles", setOf("x"), 1920, 1080)

    private fun run() = RecordedEngineRun.load(
        listOf("window_result.json", "manifest.json").map { "h13g_w1/$it" to File(runDir, it).readText() },
    ).single()

    @Test fun storeKeepsEveryRealRow() {
        val r = run()
        val store = WindowResultStore(r.result.analysisId, 1920, 1080)
        val put = store.put(r.result, r.engineVersion)
        println("put=$put rows=${r.result.ball.size} kept=${store.track().size} coverage=${store.coverageUs}")
        // 302 rows on 204 distinct frames: every frame survives (several candidates per frame are allowed).
        assertThat(store.track().size).isEqualTo(204)
        // 37.02 s holds two candidates: (716.2, 735.0) conf 0.6081 and the static (1260, 300) conf 0.5122.
        val i = store.track().indexAt(37_020_000)
        assertThat(store.track().x[i]).isEqualTo(716.2f)
    }

    @Test fun oneCandidatePerDistinctFrameAndTheEnginesFrameCountAsDenominator() {
        val r = run()
        val distinct = r.result.ball.map { it[0].toString().toDouble().toLong() }.toSet().size
        val t = r.candidateTrack(clip)!!
        println("distinct=$distinct framesWithCandidate=${t.framesWithCandidate} framesInRange=${t.track.framesInRange} emitted=${r.framesEmitted}")
        assertThat(t.framesWithCandidate).isEqualTo(distinct)
        assertThat(t.track.framesInRange).isEqualTo(300) // manifest summary.frames_emitted
    }
}
