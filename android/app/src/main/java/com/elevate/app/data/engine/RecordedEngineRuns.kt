package com.elevate.app.data.engine

import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.WindowResult
import com.elevate.app.domain.analysis.WindowResultStore.PutResult
import com.elevate.app.domain.analysis.WindowResultStore
import com.elevate.app.domain.model.ClipIdentity
import com.elevate.app.domain.model.TrailTrack
import com.elevate.app.playback.WindowTrailAdapter
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Engine output recorded by Person 1 on Modal (`engine/eval/runs/<run>/`, copied into the assets
 * at build time): a real `window_result.v1` plus its manifest. It is **not** a live analysis of
 * the user's upload, and the screen says so. The manifest names the clip but not a file hash, so
 * a run is only offered on a file whose bytes already matched the hand-label fixture for the same
 * clip id (sha256 in `fixtures/fixture_trail.json`): [clip] comes from that verified match.
 */
class RecordedEngineRun(
    val runId: String,
    /** Clip id from the manifest file name, e.g. `h13g_singles` for `h13g_singles.mp4`. */
    val clipId: String,
    val engineVersion: String,
    val modelIds: List<String>,
    /** Measured by the engine on the window's frames (manifest `summary.quality`). */
    val ballCoverage: Double?,
    /** Frames the engine emitted in this window (manifest `summary.frames_emitted`): the denominator. */
    val framesEmitted: Int?,
    val result: WindowResult,
    val modalProofNote: String?,
) {
    /** Candidate track on [clip] (highest-confidence candidate per frame), or null when empty. */
    fun candidateTrack(clip: ClipIdentity): CandidateTrail? {
        val store = WindowResultStore(result.analysisId, clip.width, clip.height)
        if (store.put(result, engineVersion) !is PutResult.Stored) return null
        val t = WindowTrailAdapter.toTrail(store.track(), clip, engineVersion, store.coverageUs) ?: return null
        if (t.candidateRows == 0) return null
        val c = t.candidate
        // The adapter counts only frames that have rows; the engine knows how many frames it read.
        val track = framesEmitted?.takeIf { it >= c.labelledFrames }?.let { n ->
            TrailTrack(c.clip, c.provenance, c.rangeStartUs, c.rangeEndUs, n, c.tUs, c.x, c.y)
        } ?: c
        return CandidateTrail(track, framesWithCandidate = t.candidateRows, rowsTotal = result.ball.size)
    }

    class CandidateTrail(val track: TrailTrack, val framesWithCandidate: Int, val rowsTotal: Int)

    companion object {
        /** [files]: (path inside `engine_runs/`, text). Unreadable runs are skipped, never guessed. */
        fun load(files: List<Pair<String, String>>): List<RecordedEngineRun> =
            files.groupBy { it.first.substringBefore('/') }.mapNotNull { (run, entries) ->
                val wr = entries.firstOrNull { it.first.endsWith("/window_result.json") }?.second ?: return@mapNotNull null
                val mf = entries.firstOrNull { it.first.endsWith("/manifest.json") }?.second ?: return@mapNotNull null
                runCatching {
                    val result = Contracts.json.decodeFromString(WindowResult.serializer(), wr)
                    val m = Contracts.json.parseToJsonElement(mf).jsonObject
                    val clipFile = m["clip"]!!.jsonPrimitive.content
                    val version = m["engine_version"]!!.jsonPrimitive.content
                    val summary = m["summary"]?.jsonObject
                    val quality = summary?.get("quality")?.jsonObject
                    RecordedEngineRun(
                        runId = run,
                        clipId = clipFile.substringBeforeLast('.'),
                        engineVersion = version,
                        modelIds = result.models,
                        ballCoverage = quality?.get("ball_coverage")?.jsonPrimitive?.content?.toDoubleOrNull(),
                        framesEmitted = summary?.get("frames_emitted")?.jsonPrimitive?.content?.toIntOrNull(),
                        result = result,
                        modalProofNote = m["engine_commit"]?.jsonPrimitive?.content,
                    )
                }.getOrNull()
            }
    }
}
