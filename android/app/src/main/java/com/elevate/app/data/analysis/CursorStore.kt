package com.elevate.app.data.analysis

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.Contracts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * The applied, contiguous events of one analysis, saved so a restart shows the last known state at
 * once (marked "catching up") and asks only for events after [Saved.afterSeq].
 */
interface CursorStore {
    data class Saved(val analysisId: String, val afterSeq: Long, val events: List<AnalysisEvent>, val savedAtMs: Long)

    fun load(analysisId: String): Saved?
    fun save(saved: Saved)
    fun clear(analysisId: String)
}

/**
 * One JSON file per analysis in [dir] (app-private, e.g. `filesDir/analysis`). App-internal format,
 * not a contract. Written to a temp file and renamed, so a crash mid-write leaves the previous file.
 * A file that does not validate (other analysis, seq not contiguous from 1, cursor not the last
 * seq) is ignored and the analysis is fetched from seq 0 instead.
 */
class FileCursorStore(private val dir: File) : CursorStore {

    @Serializable
    private data class Doc(
        val format: String,
        @SerialName("analysis_id") val analysisId: String,
        @SerialName("after_seq") val afterSeq: Long,
        @SerialName("saved_at_ms") val savedAtMs: Long,
        val events: List<AnalysisEvent>,
    )

    private fun file(id: String) = File(dir, "cursor_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    override fun load(analysisId: String): CursorStore.Saved? {
        val f = file(analysisId)
        if (!f.isFile) return null
        val doc = try {
            Contracts.json.decodeFromString(Doc.serializer(), f.readText())
        } catch (e: Exception) {
            return null
        }
        if (doc.format != FORMAT || doc.analysisId != analysisId) return null
        val seqs = doc.events.map { it.seq }
        if (seqs != (1L..seqs.size.toLong()).toList()) return null
        if (doc.afterSeq != (seqs.lastOrNull() ?: 0L)) return null
        if (doc.events.any { it.analysisId != analysisId }) return null
        return CursorStore.Saved(analysisId, doc.afterSeq, doc.events, doc.savedAtMs)
    }

    override fun save(saved: CursorStore.Saved) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        val text = Contracts.json.encodeToString(Doc.serializer(), Doc(FORMAT, saved.analysisId, saved.afterSeq, saved.savedAtMs, saved.events))
        val target = file(saved.analysisId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Windows (JVM tests) cannot rename over an existing file.
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("cannot replace ${target.name}")
        }
    }

    override fun clear(analysisId: String) {
        file(analysisId).delete()
    }

    companion object {
        const val FORMAT = "elevate.analysis_cursor.v1"
    }
}
