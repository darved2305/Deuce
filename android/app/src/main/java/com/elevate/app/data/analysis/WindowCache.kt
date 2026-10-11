package com.elevate.app.data.analysis

import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.api.WindowResult
import com.elevate.app.data.upload.AtomicFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Every `window_result.v1` downloaded for one analysis, and its `match.v1` once fetched, kept on this
 * phone (`filesDir/analysis/windows/<analysisId>/`), so a match analysed earlier reopens with its
 * trail, hits and overview with no network. Each window is its own atomically written file
 * ([AtomicFile]: temp + fsync + rename), keyed by `window_seq`; the server's document is stored as
 * JSON inside a small envelope that names the analysis, the artifact and when it was cached.
 * App-internal format, not a contract. A file that does not decode or names another analysis or
 * window is ignored (and deleted), never repaired. Blocking file IO: call off the main thread.
 */
class WindowCache(val dir: File, val analysisId: String, private val clock: () -> Long = System::currentTimeMillis) {

    /** One cached window: the server's document as it was downloaded, plus where it came from. */
    data class Cached(val windowSeq: Int, val artifactId: String?, val engineVersion: String?, val result: WindowResult, val cachedAtMs: Long)

    @Serializable
    private data class WindowDoc(
        val format: String,
        @SerialName("analysis_id") val analysisId: String,
        @SerialName("window_seq") val windowSeq: Int,
        @SerialName("artifact_id") val artifactId: String? = null,
        @SerialName("engine_version") val engineVersion: String? = null,
        @SerialName("cached_at_ms") val cachedAtMs: Long,
        val window: JsonElement,
    )

    @Serializable
    private data class MatchDoc(
        val format: String,
        @SerialName("analysis_id") val analysisId: String,
        @SerialName("cached_at_ms") val cachedAtMs: Long,
        val match: JsonElement,
    )

    private fun windowFile(seq: Int) = File(dir, "w_$seq.json")
    private val matchFile get() = File(dir, MATCH_FILE)

    /** Every valid cached window, by window_seq. Unreadable or foreign files are deleted. */
    @Synchronized
    fun windows(): List<Cached> {
        val files = dir.listFiles { f -> f.isFile && WINDOW_NAME.matches(f.name) } ?: return emptyList()
        return files.mapNotNull { f ->
            val seq = WINDOW_NAME.matchEntire(f.name)!!.groupValues[1].toIntOrNull()
            val c = seq?.let { decodeWindow(f, it) }
            if (c == null) AtomicFile(f).delete()
            c
        }.sortedBy { it.windowSeq }
    }

    private fun decodeWindow(f: File, seq: Int): Cached? = try {
        val doc = Contracts.json.decodeFromString(WindowDoc.serializer(), AtomicFile(f).readText() ?: return null)
        val w = Contracts.json.decodeFromJsonElement(WindowResult.serializer(), doc.window)
        val ok = doc.format == FORMAT && doc.analysisId == analysisId && doc.windowSeq == seq &&
            w.windowSeq == seq && w.analysisId == analysisId && w.schema == "window_result.v1"
        if (ok) Cached(seq, doc.artifactId, doc.engineVersion, w, doc.cachedAtMs) else null
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException: a corrupt or older file.
        null
    } catch (e: java.io.IOException) {
        null
    }

    /** Stores [result] (replacing any earlier copy of its window_seq). Throws IOException when the disk refuses. */
    @Synchronized
    fun put(result: WindowResult, artifactId: String?, engineVersion: String?) {
        require(result.analysisId == analysisId) { "window of another analysis" }
        val doc = WindowDoc(FORMAT, analysisId, result.windowSeq, artifactId, engineVersion, clock(), Contracts.json.encodeToJsonElement(WindowResult.serializer(), result))
        AtomicFile(windowFile(result.windowSeq)).writeText(Contracts.json.encodeToString(WindowDoc.serializer(), doc))
    }

    /** The cached `match.v1` and when it was cached, or null (none, corrupt, or another analysis). */
    @Synchronized
    fun match(): Pair<MatchRecord, Long>? = try {
        val text = AtomicFile(matchFile).readText()
        if (text == null) null else {
            val doc = Contracts.json.decodeFromString(MatchDoc.serializer(), text)
            val m = Contracts.json.decodeFromJsonElement(MatchRecord.serializer(), doc.match)
            if (doc.format == FORMAT && doc.analysisId == analysisId && m.schema == "match.v1") m to doc.cachedAtMs else null
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    @Synchronized
    fun putMatch(match: MatchRecord) {
        val doc = MatchDoc(FORMAT, analysisId, clock(), Contracts.json.encodeToJsonElement(MatchRecord.serializer(), match))
        AtomicFile(matchFile).writeText(Contracts.json.encodeToString(MatchDoc.serializer(), doc))
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        const val FORMAT = "elevate.window_cache.v1"
        const val MATCH_FILE = "match.json"
        private val WINDOW_NAME = Regex("""w_(\d{1,9})\.json""")

        /** `filesDir/analysis/windows/<analysisId>/` (the id made file-name safe). */
        fun forAnalysis(filesDir: File, analysisId: String, clock: () -> Long = System::currentTimeMillis): WindowCache =
            WindowCache(File(File(File(filesDir, "analysis"), "windows"), analysisId.replace(Regex("[^A-Za-z0-9_-]"), "_")), analysisId, clock)
    }
}
