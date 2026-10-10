package com.elevate.app.data.fixture

import com.elevate.app.domain.model.ClipIdentity
import com.elevate.app.domain.model.Provenance
import com.elevate.app.domain.model.TrailTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses `elevate.trail_labels.v1` (written by android/tools/labeller). Rejects anything it cannot
 * trust instead of repairing it: a broken fixture must show "no annotations", never a wrong trail.
 */
object TrailLabelsParser {

    const val SCHEMA = "elevate.trail_labels.v1"

    private val json = Json { ignoreUnknownKeys = true }

    class InvalidFixture(message: String) : Exception(message)

    @Serializable
    private data class Doc(
        val schema: String,
        val clip: Clip,
        val provenance: Prov,
        @SerialName("range_us") val rangeUs: List<Long>,
        @SerialName("frame_count_in_range") val frameCountInRange: Int,
        val frames: List<Frame>,
    )

    @Serializable
    private data class Clip(
        val id: String,
        val width: Int,
        val height: Int,
        val rotation: Int = 0,
        @SerialName("accepted_file_sha256") val acceptedFileSha256: List<String> = emptyList(),
    )

    @Serializable
    private data class Prov(
        val state: String,
        @SerialName("engine_version") val engineVersion: String,
        @SerialName("labelled_by") val labelledBy: String? = null,
        val tool: String? = null,
    )

    @Serializable
    private data class Frame(@SerialName("t_us") val tUs: Long, val ball: List<Float>? = null)

    fun parse(text: String): TrailTrack {
        val doc = try {
            json.decodeFromString(Doc.serializer(), text)
        } catch (e: Exception) {
            throw InvalidFixture("not a valid trail_labels document: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (doc.schema != SCHEMA) throw InvalidFixture("schema ${doc.schema} is not $SCHEMA")
        val c = doc.clip
        if (c.width <= 0 || c.height <= 0) throw InvalidFixture("clip size ${c.width}x${c.height}")
        if (c.acceptedFileSha256.isEmpty()) throw InvalidFixture("no accepted_file_sha256: the source video cannot be verified")
        if (c.acceptedFileSha256.any { !it.matches(Regex("[0-9a-f]{64}")) }) throw InvalidFixture("malformed sha256")
        if (doc.rangeUs.size != 2 || doc.rangeUs[0] > doc.rangeUs[1]) throw InvalidFixture("range_us ${doc.rangeUs}")

        // Sort by time; an identical duplicate is harmless, a conflicting one means the labels
        // cannot be trusted for that frame, so the whole document is rejected.
        val sorted = doc.frames.sortedBy { it.tUs }
        val kept = ArrayList<Frame>(sorted.size)
        for (f in sorted) {
            if (f.tUs !in doc.rangeUs[0]..doc.rangeUs[1]) throw InvalidFixture("frame ${f.tUs} outside range_us")
            val b = f.ball
            if (b != null) {
                if (b.size != 2) throw InvalidFixture("frame ${f.tUs}: ball must be [x, y] or null")
                val (x, y) = b
                if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > c.width || y > c.height) {
                    throw InvalidFixture("frame ${f.tUs}: ($x, $y) outside ${c.width}x${c.height}")
                }
            }
            val prev = kept.lastOrNull()
            if (prev != null && prev.tUs == f.tUs) {
                if (prev.ball != f.ball) throw InvalidFixture("frame ${f.tUs} labelled twice with different values")
                continue
            }
            kept += f
        }
        if (kept.size > doc.frameCountInRange) throw InvalidFixture("more labels than frames in range")

        val p = doc.provenance
        // Contracts §0 provenance states; a hand label must say so in both fields.
        if (p.state !in setOf("observed", "candidate", "inferred", "user")) throw InvalidFixture("unknown provenance state ${p.state}")
        if ((p.state == "user") != (p.engineVersion == "hand-labelled")) {
            throw InvalidFixture("state ${p.state} with engine_version ${p.engineVersion}: hand labels need state user + engine_version hand-labelled")
        }
        return TrailTrack(
            clip = ClipIdentity(c.id, c.acceptedFileSha256.toSet(), c.width, c.height),
            provenance = Provenance(p.state, p.engineVersion, p.labelledBy, p.tool),
            rangeStartUs = doc.rangeUs[0],
            rangeEndUs = doc.rangeUs[1],
            framesInRange = doc.frameCountInRange,
            tUs = LongArray(kept.size) { kept[it].tUs },
            x = FloatArray(kept.size) { kept[it].ball?.get(0) ?: Float.NaN },
            y = FloatArray(kept.size) { kept[it].ball?.get(1) ?: Float.NaN },
        )
    }
}
