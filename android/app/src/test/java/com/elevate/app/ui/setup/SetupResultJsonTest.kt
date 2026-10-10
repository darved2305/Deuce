package com.elevate.app.ui.setup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import java.io.File

/**
 * [SetupResult.toJson] against `session.create.request.v1.schema.json` (packages/contracts).
 * Only the fields this flow produces are checked: `source`, `rally_candidates` and `upload_mode`
 * are added by the caller, so they are dropped from `required` here and nothing else is relaxed.
 */
class SetupResultJsonTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/contracts/schemas").isDirectory }

    private val schema: JsonObject = run {
        val full = Json.parseToJsonElement(File(repo, "packages/contracts/schemas/session.create.request.v1.schema.json").readText()).jsonObject
        val notOurs = setOf("source", "rally_candidates", "upload_mode")
        val required = full.getValue("required").jsonArray.filterNot { it.jsonPrimitive.content in notOurs }
        JsonObject(full + ("required" to JsonArray(required)))
    }

    private val badminton = SetupResult(
        sport = Sport.Badminton,
        format = Format.Singles,
        ruleset = Ruleset("bwf_3x21", "2025-04"),
        mode = Mode.Match,
        calibrationInput = CalibrationInput(
            method = "tap", plane = "court", template = "badminton_singles",
            pointsPx = PointsPx(listOf(300.0, 1000.0), listOf(1620.0, 1000.0), listOf(1250.0, 300.0), listOf(670.0, 300.0)),
            frameMs = 1200,
        ),
        whoIsMe = TimedPoint(listOf(960.0, 700.5), 1200),
        freePlaySeed = null,
    )

    @Test fun badmintonResultMatchesTheSchema() {
        val json = badminton.toJson()
        assertThat(validate(schema, json, "$")).isEmpty()
        assertThat(json.keys).containsExactly("sport", "format", "ruleset", "mode", "calibration_input", "who_is_me", "free_play_seed")
        // Required-but-null fields are present as JSON null, never omitted.
        assertThat(json["free_play_seed"]).isEqualTo(JsonNull)
        assertThat(json["sport"]!!.jsonPrimitive.content).isEqualTo("badminton")
        val cal = json["calibration_input"]!!.jsonObject
        assertThat(cal["points_px"]!!.jsonObject.keys).containsExactly("near_left", "near_right", "far_right", "far_left").inOrder()
        assertThat(cal["frame_ms"]!!.jsonPrimitive.longOrNull).isEqualTo(1200)
    }

    @Test fun reducerOutputMatchesTheSchema() {
        val corners = listOf(300 to 1000, 1620 to 1000, 1250 to 300, 670 to 300)
        var s = reduce(SetupState(1920.0, 1080.0, frameMs = 4321), SetupAction.SelectFormat(Format.Doubles))
        s = reduce(s, SetupAction.Next)
        for ((x, y) in corners) s = reduce(s, SetupAction.Tap(com.elevate.app.domain.calibration.ImagePoint(x.toDouble(), y.toDouble())))
        s = reduce(reduce(s, SetupAction.Next), SetupAction.Tap(com.elevate.app.domain.calibration.ImagePoint(10.0, 20.0)))
        val json = s.result()!!.toJson()
        assertThat(validate(schema, json, "$")).isEmpty()
        assertThat(json["calibration_input"]!!.jsonObject["template"]!!.jsonPrimitive.content).isEqualTo("badminton_doubles")
        assertThat(json["format"]!!.jsonPrimitive.content).isEqualTo("doubles")
    }

    @Test fun freePlayShapeMatchesTheSchema() {
        // Shape only: free play is not offered in this build (no pinned ruleset version).
        val fp = badminton.copy(
            sport = Sport.FreePlay, format = Format.None, mode = Mode.FreePlay,
            calibrationInput = null, freePlaySeed = TimedPoint(listOf(812.0, 431.0), 2400),
        )
        val json = fp.toJson()
        assertThat(validate(schema, json, "$")).isEmpty()
        assertThat(json["calibration_input"]).isEqualTo(JsonNull)
        assertThat(json["free_play_seed"]!!.jsonObject.keys).containsExactly("t_ms", "image_xy")
    }

    @Test fun theValidatorCatchesContractBreaks() {
        val good = badminton.toJson()
        val noWho = JsonObject(good - "who_is_me")
        assertWithMessage("missing required").that(validate(schema, noWho, "$")).isNotEmpty()
        val extra = JsonObject(good + ("speed_kmh" to JsonPrimitive(300)))
        assertWithMessage("unknown key").that(validate(schema, extra, "$")).isNotEmpty()
        val badEnum = JsonObject(good + ("sport" to JsonPrimitive("squash")))
        assertWithMessage("enum").that(validate(schema, badEnum, "$")).isNotEmpty()
        val cal = good["calibration_input"]!!.jsonObject
        val threeD = JsonObject(cal + ("points_px" to JsonObject(cal["points_px"]!!.jsonObject + ("near_left" to JsonArray(listOf(1, 2, 3).map { JsonPrimitive(it) })))))
        assertWithMessage("pair length").that(validate(schema, JsonObject(good + ("calibration_input" to threeD)), "$")).isNotEmpty()
    }

    /**
     * Minimal JSON Schema (2020-12) check for the keywords this schema uses: type, enum, const,
     * properties, required, additionalProperties false, anyOf, prefixItems, items false,
     * min/maxItems, minimum/maximum. Returns the problems found; empty means valid.
     */
    private fun validate(s: JsonObject, v: JsonElement, at: String): List<String> {
        s["anyOf"]?.let { alts ->
            val ok = alts.jsonArray.any { validate(it.jsonObject, v, at).isEmpty() }
            return if (ok) emptyList() else listOf("$at: matches no anyOf branch")
        }
        val out = mutableListOf<String>()
        s["type"]?.jsonPrimitive?.content?.let { t -> if (!hasType(v, t)) return listOf("$at: not $t") }
        s["const"]?.let { if (it != v) out += "$at: not const $it" }
        s["enum"]?.let { if (v !in it.jsonArray) out += "$at: $v not in enum" }
        if (v is JsonPrimitive && !v.isString && v != JsonNull) {
            val d = v.content.toDoubleOrNull()
            s["minimum"]?.jsonPrimitive?.content?.toDouble()?.let { if (d == null || d < it) out += "$at: below minimum" }
            s["maximum"]?.jsonPrimitive?.content?.toDouble()?.let { if (d == null || d > it) out += "$at: above maximum" }
        }
        if (v is JsonObject) {
            val props = s["properties"]?.jsonObject.orEmpty()
            s["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in v) out += "$at: missing ${r.jsonPrimitive.content}" }
            if ((s["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
                (v.keys - props.keys).forEach { out += "$at: unexpected $it" }
            }
            for ((k, child) in v) props[k]?.let { out += validate(it.jsonObject, child, "$at.$k") }
        }
        if (v is JsonArray) {
            s["minItems"]?.jsonPrimitive?.content?.toInt()?.let { if (v.size < it) out += "$at: too few items" }
            s["maxItems"]?.jsonPrimitive?.content?.toInt()?.let { if (v.size > it) out += "$at: too many items" }
            val prefix = s["prefixItems"]?.jsonArray.orEmpty()
            v.forEachIndexed { i, e ->
                if (i < prefix.size) out += validate(prefix[i].jsonObject, e, "$at[$i]")
                else if ((s["items"] as? JsonPrimitive)?.booleanOrNull == false) out += "$at[$i]: no extra items allowed"
            }
        }
        return out
    }

    private fun hasType(v: JsonElement, t: String): Boolean = when (t) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "null" -> v == JsonNull
        "string" -> v is JsonPrimitive && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v != JsonNull && v.content.toDoubleOrNull() != null
        else -> false
    }
}
