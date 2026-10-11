package com.elevate.app.data.history

import com.elevate.app.data.upload.AtomicFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One analysed (or analysing) match remembered on this phone, so it can be reopened without a new
 * upload. Every value is copied from something measured or returned by the server: the probe
 * (duration, size), the fingerprint (sha256), the session/analysis responses (ids) and the applied
 * events (state, windows, coverage, engine version). App-internal format, not a contract.
 */
@Serializable
data class MatchHistoryEntry(
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("match_id") val matchId: String? = null,
    val sha256: String,
    val title: String,
    /** The picked content Uri (string form). Readable later only if a persistable grant was taken. */
    @SerialName("source_uri") val sourceUri: String,
    /** App-private staging copy made when the grant could not be persisted, if it still exists. */
    @SerialName("staged_path") val stagedPath: String? = null,
    /** Contract sport string ("badminton"), null when unknown. */
    val sport: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Wall-clock ms when the analysis was first recorded here. */
    @SerialName("created_at") val createdAt: Long,
    /** Wall-clock ms of the last recorded change (state, windows, coverage, engine). */
    @SerialName("updated_at") val updatedAt: Long = createdAt,
    /** Last analysis state seen, exact contract string ("processing", "partial", "completed", "failed"). */
    @SerialName("last_state") val lastState: String? = null,
    @SerialName("windows_done") val windowsDone: Int = 0,
    /** Merged `emit_range_us` of applied `window.completed` events, in ms. */
    @SerialName("coverage_ms") val coverageMs: Long = 0,
    @SerialName("engine_version") val engineVersion: String? = null,
    /**
     * Wall-clock ms when this phone first saw the analysis finished (server `completed`, or every
     * window in: [HistoryStatus.Ready] / [HistoryStatus.ReadyFromWindows]); never moved afterwards.
     */
    @SerialName("completed_at") val completedAt: Long? = null,
    /** App-private copy of the video kept for history (`filesDir/videos/<sha256>.<ext>`), if any. */
    @SerialName("local_copy_path") val localCopyPath: String? = null,
)

/**
 * The time the history card shows: when the analysis finished for a ready match (entries recorded
 * before [MatchHistoryEntry.completedAt] existed fall back to the last update), when it started for
 * one still analysing, and when it stopped for a failed one.
 */
fun MatchHistoryEntry.cardTimeMs(): Long = when (status()) {
    HistoryStatus.Ready, HistoryStatus.ReadyFromWindows -> completedAt ?: updatedAt
    HistoryStatus.Analysing -> createdAt
    HistoryStatus.Failed -> updatedAt
}

/** What a history row may claim, derived from the recorded state only. */
enum class HistoryStatus {
    /** Not finished and the windows do not cover the clip yet (last known state). */
    Analysing,
    /** The server state is not `completed`, but the completed windows cover the whole clip. */
    ReadyFromWindows,
    /** The server said `completed`. */
    Ready,
    Failed,
}

/** Coverage counts as the whole clip when it is within this many ms of the duration (display precision 0.1 s). */
internal const val FULL_COVERAGE_SLACK_MS = 50L

fun MatchHistoryEntry.status(): HistoryStatus = when {
    lastState == "failed" -> HistoryStatus.Failed
    lastState == "completed" -> HistoryStatus.Ready
    windowsDone > 0 && durationMs != null && durationMs > 0 && coverageMs + FULL_COVERAGE_SLACK_MS >= durationMs -> HistoryStatus.ReadyFromWindows
    else -> HistoryStatus.Analysing
}

/**
 * All remembered matches in one atomically written JSON file (temp + fsync + rename, [AtomicFile]):
 * a crash mid-write leaves the previous list. Upsert by analysisId, newest first, at most [cap]
 * entries (the oldest are dropped). A file that cannot be read is treated as empty. Thread-safe.
 * Pure JVM: no Android types.
 */
class MatchHistory(file: File, private val cap: Int = DEFAULT_CAP) {

    @Serializable
    private data class Doc(val format: String, val entries: List<MatchHistoryEntry>)

    private val atomic = AtomicFile(file)
    private var cache: List<MatchHistoryEntry>? = null

    init {
        require(cap > 0) { "cap must be positive" }
    }

    /** Newest first (by [MatchHistoryEntry.createdAt]). */
    @Synchronized
    fun list(): List<MatchHistoryEntry> = cache ?: read().also { cache = it }

    @Synchronized
    fun get(analysisId: String): MatchHistoryEntry? = list().firstOrNull { it.analysisId == analysisId }

    /**
     * Inserts or replaces the entry with the same analysisId. A replaced entry keeps its original
     * [MatchHistoryEntry.createdAt], so its place in the list does not move.
     */
    @Synchronized
    fun upsert(entry: MatchHistoryEntry) {
        val current = list()
        val old = current.firstOrNull { it.analysisId == entry.analysisId }
        val merged = if (old != null) entry.copy(createdAt = old.createdAt) else entry
        write(current.filter { it.analysisId != entry.analysisId } + merged)
    }

    /** Applies [change] to the entry with [analysisId]; returns the new entry, or null when there is none or nothing changed. */
    @Synchronized
    fun update(analysisId: String, change: (MatchHistoryEntry) -> MatchHistoryEntry): MatchHistoryEntry? {
        val current = list()
        val old = current.firstOrNull { it.analysisId == analysisId } ?: return null
        val new = change(old).copy(analysisId = old.analysisId, createdAt = old.createdAt)
        if (new == old) return null
        write(current.map { if (it.analysisId == analysisId) new else it })
        return new
    }

    @Synchronized
    fun remove(analysisId: String): Boolean {
        val current = list()
        if (current.none { it.analysisId == analysisId }) return false
        write(current.filter { it.analysisId != analysisId })
        return true
    }

    private fun write(entries: List<MatchHistoryEntry>) {
        val sorted = entries.sortedByDescending { it.createdAt }.take(cap)
        atomic.writeText(json.encodeToString(Doc.serializer(), Doc(FORMAT, sorted)))
        cache = sorted
    }

    private fun read(): List<MatchHistoryEntry> {
        val text = runCatching { atomic.readText() }.getOrNull() ?: return emptyList()
        val doc = runCatching { json.decodeFromString(Doc.serializer(), text) }.getOrNull() ?: return emptyList()
        if (doc.format != FORMAT) return emptyList()
        return doc.entries.distinctBy { it.analysisId }.sortedByDescending { it.createdAt }.take(cap)
    }

    companion object {
        const val FORMAT = "elevate.match_history.v1"
        const val DEFAULT_CAP = 50
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
