package com.elevate.app.domain.analysis

import com.elevate.app.data.api.WindowEvent
import com.elevate.app.data.api.WindowResult
import com.elevate.app.data.events.EventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Ball positions merged from every downloaded `window_result.v1` (contracts §5), as columns sorted
 * by time: per-frame lookup is a binary search on [tUs], no JSON and no allocation.
 *
 * [conf] is NaN where the engine gave `null`. [state] holds [BallTrack.OBSERVED] … [BallTrack.USER].
 */
class BallTrack(
    val tUs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val conf: FloatArray,
    val state: ByteArray,
    /** engine_version of the window each row came from (index into [engineVersions]). */
    val engineIndex: ByteArray,
    val engineVersions: List<String?>,
) {
    init {
        require(tUs.size == x.size && x.size == y.size && y.size == conf.size && conf.size == state.size && state.size == engineIndex.size) { "column sizes differ" }
    }

    val size: Int get() = tUs.size

    /** Index of the row at [timeUs] within ±[toleranceUs], else -1. */
    fun indexAt(timeUs: Long, toleranceUs: Long = 0): Int {
        var lo = 0
        var hi = tUs.size
        val key = timeUs - toleranceUs
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tUs[mid] < key) lo = mid + 1 else hi = mid
        }
        return if (lo < tUs.size && tUs[lo] <= timeUs + toleranceUs) lo else -1
    }

    fun count(stateCode: Byte): Int = state.count { it == stateCode }

    companion object {
        const val OBSERVED: Byte = 0
        const val CANDIDATE: Byte = 1
        const val INFERRED: Byte = 2
        const val USER: Byte = 3
        val STATES = listOf("observed", "candidate", "inferred", "user")
        fun code(state: String): Byte? = STATES.indexOf(state).takeIf { it >= 0 }?.toByte()

        val EMPTY = BallTrack(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0), ByteArray(0), ByteArray(0), emptyList())
    }
}

/**
 * Merges window results for one analysis. Pure (no I/O); call from one thread or confine it.
 *
 * Rules (contracts §3, §5):
 *  - keyed by `window_seq`: a re-delivered window replaces the stored one, unless the stored one
 *    has a newer `engine_version` (a late duplicate from an older run never wins);
 *  - each window owns ONLY its `emit_range_us`; rows in the decode context outside it are dropped,
 *    so overlapping windows never double-count. A row exactly on a shared boundary belongs to the
 *    later window (higher `window_seq`);
 *  - arrival order does not matter; a failed window leaves its range empty;
 *  - rows that fail validation (shape, non-finite or off-image coordinates, unknown state,
 *    confidence outside 0..1) are rejected and counted, never repaired.
 */
class WindowResultStore(
    val analysisId: String,
    /** Image space (source pixels after rotation) when known; rows outside it are rejected. */
    private val sourceWidth: Int? = null,
    private val sourceHeight: Int? = null,
) {
    data class Window(
        val windowSeq: Int,
        val engineVersion: String?,
        val emitUs: LongRange,
        val models: List<String>,
        /** Kept ball rows in t order, already inside [emitUs]. */
        val rows: List<Row>,
        val playerRowsKept: Int,
        val droppedOutsideEmit: Int,
        val rejected: Int,
        val rejectReasons: List<String>,
        val ballCoverage: Double,
        /** Kept `type: "hit"` events in t order, inside [emitUs]. Other event types are ignored. */
        val hits: List<HitCandidate> = emptyList(),
    )

    data class Row(val tUs: Long, val x: Float, val y: Float, val conf: Float, val state: Byte)

    sealed interface PutResult {
        data class Stored(val window: Window, val replaced: Boolean) : PutResult
        /** Older engine_version than the stored window: ignored. */
        data class KeptNewer(val windowSeq: Int, val stored: String?, val offered: String?) : PutResult
        data class Rejected(val reason: String) : PutResult
    }

    private val windows = sortedMapOf<Int, Window>()
    private val failed = sortedMapOf<Int, String>()
    private var cached: BallTrack? = null

    val stored: List<Window> get() = windows.values.toList()
    val failedWindows: Map<Int, String> get() = failed.toMap()
    /** Hit candidates of every stored window, by time. Each window owns only its emit range. */
    fun hits(): List<HitCandidate> = windows.values.flatMap { it.hits }.sortedBy { it.tMs }

    val rejectedRows: Int get() = windows.values.sumOf { it.rejected }
    val droppedOutsideEmit: Int get() = windows.values.sumOf { it.droppedOutsideEmit }

    /** Merged emit ranges of the stored windows: what the merged track can claim to cover. */
    val coverageUs: List<LongRange> get() = EventLog.merge(windows.values.map { it.emitUs })

    fun put(result: WindowResult, engineVersion: String?): PutResult {
        if (result.schema != "window_result.v1") return PutResult.Rejected("schema ${result.schema} is not window_result.v1")
        if (result.analysisId != analysisId) return PutResult.Rejected("window for another analysis")
        val e = result.emitRangeUs
        if (e.size != 2 || e[0] < 0 || e[0] > e[1]) return PutResult.Rejected("emit_range_us $e is not [start, end]")
        val d = result.decodeWindowUs
        if (d.size != 2 || d[0] > d[1]) return PutResult.Rejected("decode_window_us $d is not [start, end]")
        val existing = windows[result.windowSeq]
        if (existing != null && compareVersions(existing.engineVersion, engineVersion) > 0) {
            return PutResult.KeptNewer(result.windowSeq, existing.engineVersion, engineVersion)
        }
        val emit = e[0]..e[1]
        val rows = ArrayList<Row>(result.ball.size)
        var outside = 0
        val reasons = ArrayList<String>()
        for (raw in result.ball) {
            val row = parseRow(raw) { reasons += it }
            if (row == null) continue
            if (row.tUs !in emit) { outside++; continue }
            rows += row
        }
        rows.sortBy { it.tUs }
        // Several rows for one frame: identical copies collapse. Several *candidates* are normal
        // (the tracker proposes more than one blob per frame; the reader picks among them). Two
        // different observed/inferred/user positions for one frame contradict each other: rejected.
        val deduped = ArrayList<Row>(rows.size)
        for (group in rows.groupBy { it.tUs }.values) {
            val distinct = group.distinct()
            when {
                distinct.size == 1 -> deduped += distinct[0]
                distinct.all { it.state == BallTrack.CANDIDATE } -> deduped += distinct
                else -> reasons += "t ${group[0].tUs} µs given ${group.size} times with different values"
            }
        }
        val hits = ArrayList<HitCandidate>()
        val seenEvents = HashSet<String>()
        for (ev in result.events) {
            if (ev.type != HIT_EVENT) continue
            if (!seenEvents.add(ev.eventId)) continue
            val hit = parseHit(ev) { reasons += it } ?: continue
            if (hit.tMs * 1000 !in emit) continue // another window owns it (decode context)
            hits += hit
        }
        hits.sortBy { it.tMs }
        val players = result.players.count { p -> (p.getOrNull(0) as? JsonPrimitive)?.longOrNull?.let { it * 1000 in emit } == true }
        val w = Window(
            windowSeq = result.windowSeq,
            engineVersion = engineVersion,
            emitUs = emit,
            models = result.models,
            rows = deduped,
            playerRowsKept = players,
            droppedOutsideEmit = outside,
            rejected = reasons.size,
            rejectReasons = reasons.take(MAX_REASONS),
            ballCoverage = result.quality.ballCoverage,
            hits = hits,
        )
        windows[result.windowSeq] = w
        failed.remove(result.windowSeq)
        cached = null
        return PutResult.Stored(w, replaced = existing != null)
    }

    /** `window.failed`: the range stays a gap. A stored success for the same seq is kept (it is real). */
    fun markFailed(windowSeq: Int, reason: String) {
        if (windowSeq !in windows) failed[windowSeq] = reason
    }

    /** The merged track; rebuilt only after a change. */
    fun track(): BallTrack {
        cached?.let { return it }
        val versions = windows.values.map { it.engineVersion }.distinct()
        // One row per frame. At a window boundary the later window wins; inside one window, several
        // candidates for a frame resolve to the highest confidence (null confidence ranks lowest).
        val all = windows.values.flatMap { w -> w.rows.map { Triple(it, versions.indexOf(w.engineVersion), w.windowSeq) } }
        val keep = all.groupBy { it.first.tUs }.toSortedMap().values.map { group ->
            val lastWindow = group.maxOf { it.third }
            group.filter { it.third == lastWindow }.maxBy { if (it.first.conf.isNaN()) -1f else it.first.conf }
        }.map { it.first to it.second }
        val n = keep.size
        return BallTrack(
            tUs = LongArray(n) { keep[it].first.tUs },
            x = FloatArray(n) { keep[it].first.x },
            y = FloatArray(n) { keep[it].first.y },
            conf = FloatArray(n) { keep[it].first.conf },
            state = ByteArray(n) { keep[it].first.state },
            engineIndex = ByteArray(n) { keep[it].second.toByte() },
            engineVersions = versions,
        ).also { cached = it }
    }

    private fun parseHit(ev: WindowEvent, reject: (String) -> Unit): HitCandidate? {
        val xy = ev.imageXy.takeIf { it.size == 2 && it.all(Double::isFinite) }?.let { it[0] to it[1] }
        return when {
            ev.tMs < 0 -> { reject("hit ${ev.eventId}: t_ms is negative"); null }
            ev.tSigmaMs < 0 -> { reject("hit ${ev.eventId}: t_sigma_ms is negative"); null }
            BallTrack.code(ev.state) == null -> { reject("hit ${ev.eventId}: state not observed|candidate|inferred|user"); null }
            else -> HitCandidate(ev.tMs, ev.tSigmaMs, ev.trackId.ifBlank { null }, xy, ev.cues, ev.state)
        }
    }

    private fun parseRow(r: JsonArray, reject: (String) -> Unit): Row? {
        if (r.size != 5) { reject("ball row has ${r.size} items, not 5"); return null }
        val t = (r[0] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val x = (r[1] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        val y = (r[2] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        val c = r[3]
        val conf = if (c is JsonNull) Float.NaN else (c as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toFloat()
        val st = (r[4] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(BallTrack::code)
        return when {
            t == null || t < 0 -> { reject("t_ms is not a non-negative integer"); null }
            x == null || y == null || !x.isFinite() || !y.isFinite() -> { reject("t_ms $t: coordinates not finite"); null }
            x < 0 || y < 0 || (sourceWidth != null && x > sourceWidth) || (sourceHeight != null && y > sourceHeight) ->
                { reject("t_ms $t: ($x, $y) outside the image"); null }
            conf == null || (!conf.isNaN() && (conf < 0f || conf > 1f)) -> { reject("t_ms $t: confidence not null or 0..1"); null }
            st == null -> { reject("t_ms $t: state not observed|candidate|inferred|user"); null }
            else -> Row(t * 1000, x.toFloat(), y.toFloat(), conf, st)
        }
    }

    companion object {
        private const val MAX_REASONS = 20
        private const val HIT_EVENT = "hit"

        /**
         * Dotted numeric comparison ("0.10.0" > "0.9.1"); null or non-numeric parts compare as 0 /
         * by string. Used only to keep a newer window over a late older duplicate.
         */
        fun compareVersions(a: String?, b: String?): Int {
            if (a == b) return 0
            if (a == null) return -1
            if (b == null) return 1
            val pa = a.split('.', '-', '+')
            val pb = b.split('.', '-', '+')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: "0"
                val y = pb.getOrNull(i) ?: "0"
                val nx = x.toLongOrNull()
                val ny = y.toLongOrNull()
                val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
                if (c != 0) return c
            }
            return 0
        }
    }
}
