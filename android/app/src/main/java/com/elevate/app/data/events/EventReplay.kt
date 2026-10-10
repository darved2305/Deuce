package com.elevate.app.data.events

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.Contracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * Replays a recorded event sequence (`fixture_events.jsonl`, contracts §11) with the spacing it
 * was recorded with (`created_at` deltas). Used to exercise the app's event handling without a
 * live job; the UI must label it a replay, never a live analysis (AGENTS.md: fixture never
 * presented as live).
 */
object EventReplay {

    class InvalidEvents(message: String) : Exception(message)

    /** One JSON object per line (blank lines ignored); every line must be a valid event row. */
    fun parseJsonl(text: String): List<AnalysisEvent> =
        text.lineSequence().withIndex().filter { it.value.isNotBlank() }.map { (i, line) ->
            try {
                Contracts.json.decodeFromString(AnalysisEvent.serializer(), line)
            } catch (e: Exception) {
                throw InvalidEvents("line ${i + 1}: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }.toList()

    /**
     * Emits the growing [EventLog] once per event, in seq order, waiting the recorded gap between
     * consecutive `created_at` times divided by [speed] (1 = as recorded). Gaps longer than
     * [maxGapMs] are capped so a long recorded idle does not stall a demo; the cap is the only
     * change to recorded timing.
     */
    fun replay(events: List<AnalysisEvent>, speed: Double = 1.0, maxGapMs: Long = 5_000): Flow<EventLog> = flow {
        require(speed > 0) { "speed must be > 0" }
        val ordered = events.sortedBy { it.seq }
        var log = EventLog.EMPTY
        var prev: Instant? = null
        for (e in ordered) {
            val t = runCatching { Instant.parse(e.createdAt) }.getOrNull()
                ?: throw InvalidEvents("seq ${e.seq}: created_at '${e.createdAt}' is not ISO-8601")
            prev?.let { p ->
                val gap = (t.toEpochMilli() - p.toEpochMilli()).coerceAtLeast(0)
                delay((minOf(gap, maxGapMs) / speed).toLong())
            }
            prev = t
            log = log.plus(listOf(e))
            emit(log)
        }
    }
}
