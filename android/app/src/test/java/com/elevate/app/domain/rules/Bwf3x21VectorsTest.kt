package com.elevate.app.domain.rules

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The shared golden vectors (packages/rules/vectors/bwf_3x21.json) run against the Kotlin port,
 * the same file packages/rules/test/rules.test.ts runs against the TypeScript engine. Every
 * vector's expected state is compared as a JSON object, so the port must reproduce it exactly.
 */
class Bwf3x21VectorsTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/rules/vectors").isDirectory }

    /** `explicitNulls = false`: a null doubles_right_player is absent from the JSON, as in the TS state. */
    private val json = Json { explicitNulls = false }

    @Test fun bwfGoldenVectors() {
        val vectors = Json.parseToJsonElement(
            File(repo, "packages/rules/vectors/bwf_3x21.json").readText(),
        ).jsonArray
        assertThat(vectors.size).isAtLeast(40)
        for ((index, element) in vectors.withIndex()) {
            val vector = element.jsonObject
            try {
                assertThat(vector.getValue("law").jsonPrimitive.content).isNotEmpty()
                assertThat(vector.getValue("note").jsonPrimitive.content).isNotEmpty()
                runVector(vector)
            } catch (e: Throwable) {
                throw AssertionError(
                    "vector $index: ${vector["note"]?.jsonPrimitive?.contentOrNull}\n" +
                        "${e::class.simpleName}: ${e.message}",
                    e,
                )
            }
        }
    }

    private fun runVector(vector: JsonObject) {
        val initial = state(vector.getValue("initial"))
        when (vector.getValue("operation").jsonPrimitive.content) {
            "undo" -> {
                val history = vector.getValue("history").jsonArray.map { state(it) }
                val actual = Bwf3x21.undo(initial, history)
                assertThat(json.encodeToJsonElement(actual)).isEqualTo(vector.getValue("expected"))
            }
            "applyPoint" -> {
                val winningTeamElement = vector.getValue("winningTeam")
                assertThat(winningTeamElement).isNotEqualTo(JsonNull)
                val winningTeam = winningTeamElement.jsonPrimitive.int
                val expected = vector.getValue("expected").jsonObject
                val throwsName = expected["throws"]?.jsonPrimitive?.contentOrNull
                if (throwsName != null) {
                    val thrown = try {
                        Bwf3x21.applyPoint(initial, winningTeam)
                        null
                    } catch (e: RulesError) {
                        e
                    }
                    assertThat(thrown).isNotNull()
                    assertThat(thrown!!::class.simpleName).isEqualTo(throwsName)
                } else {
                    val actual = Bwf3x21.applyPoint(initial, winningTeam)
                    assertThat(json.encodeToJsonElement(actual)).isEqualTo(expected)
                    assertThat(Bwf3x21.isGameOver(actual)).isEqualTo(actual.status != "in_progress")
                    assertThat(Bwf3x21.isMatchOver(actual)).isEqualTo(actual.status == "match_over")
                    assertThat(Bwf3x21.server(actual)).isEqualTo(actual.server)
                    assertThat(Bwf3x21.courtSide(actual)).isEqualTo(actual.serviceCourt)
                }
            }
            else -> throw AssertionError("unknown operation ${vector.getValue("operation")}")
        }
    }

    private fun state(element: JsonElement): RulesState = json.decodeFromJsonElement(element)
}
