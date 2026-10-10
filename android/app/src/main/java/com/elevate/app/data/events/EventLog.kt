package com.elevate.app.data.events

import com.elevate.app.data.api.AnalysisEvent

/**
 * Client-side view of one analysis's event stream (contracts §3), shared by the fixture replay
 * (Phase 1) and the live cursor (Phase 2).
 *
 * Rules from the contract: order and dedupe by `seq`; apply only the contiguous prefix (a gap means
 * refetch, never skip); `after_seq` for the next cursor call is the last contiguous seq applied;
 * only `emit_range_us` of applied `window.completed` events advances visible coverage.
 */
class EventLog private constructor(
    /** Events applied, contiguous from seq 1, in seq order. */
    val applied: List<AnalysisEvent>,
    /** Received but not yet applicable (waiting for a missing seq), keyed by seq. */
    private val pending: Map<Long, AnalysisEvent>,
) {
    /** Last contiguous seq applied (0 before the first event): the next `after_seq`. */
    val afterSeq: Long get() = applied.lastOrNull()?.seq ?: 0

    /** The lowest missing seq when later events are waiting for it, else null. */
    val missingSeq: Long? get() = if (pending.isEmpty()) null else afterSeq + 1

    /** Analysis state implied by the applied events (contracts §3 states). */
    val state: String get() = applied.fold("uploaded") { s, e -> STATE_AFTER[e.type] ?: s }

    /** Visible coverage: merged `emit_range_us` of applied `window.completed` events, µs. */
    val coverageUs: List<LongRange> get() = merge(
        applied.filter { it.type == "window.completed" }.mapNotNull { e ->
            e.emitRangeUs?.takeIf { it.size == 2 && it[0] <= it[1] }?.let { it[0]..it[1] }
        },
    )

    /** Events that failed: the window or the whole analysis, with the contract's `reason`. */
    val failures: List<AnalysisEvent> get() = applied.filter { it.type == "window.failed" || it.type == "analysis.failed" }

    /**
     * Adds a page of events (any order, duplicates allowed). An event whose seq was already applied
     * must be identical; a different event under the same seq is a protocol violation.
     */
    fun plus(events: List<AnalysisEvent>): EventLog {
        val pend = pending.toMutableMap()
        val seen = applied.associateBy { it.seq }
        for (e in events) {
            require(e.seq >= 1) { "seq ${e.seq} < 1" }
            seen[e.seq]?.let { require(it == e) { "seq ${e.seq} delivered twice with different content" }; continue }
            pend[e.seq]?.let { require(it == e) { "seq ${e.seq} delivered twice with different content" } }
            pend[e.seq] = e
        }
        val out = applied.toMutableList()
        var next = (out.lastOrNull()?.seq ?: 0) + 1
        while (true) out += pend.remove(next++) ?: break
        return EventLog(out, pend)
    }

    companion object {
        val EMPTY = EventLog(emptyList(), emptyMap())

        private val STATE_AFTER = mapOf(
            "analysis.queued" to "queued",
            "analysis.started" to "processing",
            "window.completed" to "partial",
            "analysis.partial" to "partial",
            "analysis.finalizing" to "finalizing",
            "analysis.completed" to "completed",
            "analysis.failed" to "failed",
        )

        internal fun merge(ranges: List<LongRange>): List<LongRange> {
            val out = ArrayList<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..maxOf(last.last, r.last)
                else out += r
            }
            return out
        }
    }
}
