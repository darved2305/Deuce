package com.elevate.app.data

import com.elevate.app.data.api.AnalysisCreateResponse
import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.AnalysisEventsPage
import com.elevate.app.data.api.AnalysisGetResponse
import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.ErrorBody
import com.elevate.app.data.api.ErrorEnvelope
import com.elevate.app.data.api.MatchAnalysis
import com.elevate.app.data.api.MatchArtifacts
import com.elevate.app.data.api.MatchCalibration
import com.elevate.app.data.api.MatchCapabilities
import com.elevate.app.data.api.MatchCapability
import com.elevate.app.data.api.MatchPlayer
import com.elevate.app.data.api.MatchRally
import com.elevate.app.data.api.MatchRecord
import com.elevate.app.data.api.MatchRuleset
import com.elevate.app.data.api.MatchShot
import com.elevate.app.data.api.MatchShotChosen
import com.elevate.app.data.api.RealtimeHint
import com.elevate.app.data.api.WindowEvent
import com.elevate.app.data.api.WindowQuality
import com.elevate.app.data.api.WindowResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Kotlin readers vs the generated JSON Schemas in packages/contracts/schemas (D-261010-HW2), and
 * vs the fenced examples in docs/plan/contracts.md. Fails when a schema gains, loses or renames a
 * field, or when a required field is nullable/defaulted here.
 */
class ContractSchemaTest {

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "packages/contracts/schemas").isDirectory }
    private fun schema(name: String) = Json.parseToJsonElement(File(repo, "packages/contracts/schemas/$name.schema.json").readText()).jsonObject

    /** Object variants of a schema node: itself, or each member of anyOf/oneOf. */
    private fun variants(node: JsonObject): List<JsonObject> =
        (node["anyOf"] ?: node["oneOf"])?.jsonArray?.map { it.jsonObject }?.flatMap(::variants) ?: listOf(node)

    private fun props(v: JsonObject) = v["properties"]?.jsonObject?.keys.orEmpty()
    private fun required(v: JsonObject) = v["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()

    /** True when the schema lets this property be JSON null (`type: [.., "null"]` or an anyOf member). */
    private fun allowsNull(p: JsonObject): Boolean {
        val t = p["type"]
        if (t != null && "\"null\"" in t.toString()) return true
        return (p["anyOf"] ?: p["oneOf"])?.jsonArray?.any { allowsNull(it.jsonObject) } == true
    }

    /**
     * [subset] = the reader holds only some of the schema's fields (match.v1): every field it has must
     * exist in the schema, but the schema may have more. Otherwise the field sets must be equal.
     * Required fields must be non-optional; they may be nullable only where the schema allows null.
     */
    private fun check(node: JsonObject, d: SerialDescriptor, what: String, subset: Boolean = false) {
        val vs = variants(node)
        val allProps = vs.flatMap(::props).toSet()
        val requiredEverywhere = vs.map(::required).reduce { a, b -> a intersect b }
        val names = (0 until d.elementsCount).associateBy { d.getElementName(it) }
        if (subset) assertWithMessage("$what: fields").that(allProps).containsAtLeastElementsIn(names.keys)
        else assertWithMessage("$what: fields").that(names.keys).containsExactlyElementsIn(allProps)
        for (r in requiredEverywhere) {
            val i = names[r] ?: continue
            val prop = vs.firstNotNullOf { it["properties"]?.jsonObject?.get(r)?.jsonObject }
            assertWithMessage("$what.$r is required by the schema").that(d.isElementOptional(i)).isFalse()
            if (!allowsNull(prop)) {
                assertWithMessage("$what.$r is required and non-null in the schema").that(d.getElementDescriptor(i).isNullable).isFalse()
            }
        }
        // Optional-and-nullable in the schema ⇒ the reader must accept absence (default) too.
        for ((name, i) in names) {
            if (name !in requiredEverywhere && name in allProps) {
                assertWithMessage("$what.$name is optional in the schema").that(d.isElementOptional(i)).isTrue()
            }
        }
    }

    private fun sub(node: JsonObject, vararg path: String): JsonObject {
        var n = node
        for (p in path) n = if (p == "[]") n["items"]!!.jsonObject else variants(n).first { p in props(it) }["properties"]!!.jsonObject[p]!!.jsonObject
        return n
    }

    @Test fun errorEnvelope() {
        val s = schema("error.envelope.v1")
        check(s, ErrorEnvelope.serializer().descriptor, "error.envelope.v1")
        check(sub(s, "error"), ErrorBody.serializer().descriptor, "error.envelope.v1.error")
    }

    @Test fun realtimeHint() = check(schema("realtime.hint.v1"), RealtimeHint.serializer().descriptor, "realtime.hint.v1")

    @Test fun analysisEventAndPage() {
        check(schema("analysis.event.v1"), AnalysisEvent.serializer().descriptor, "analysis.event.v1")
        val page = schema("analysis.events.page.v1")
        check(page, AnalysisEventsPage.serializer().descriptor, "analysis.events.page.v1")
        check(sub(page, "events", "[]"), AnalysisEvent.serializer().descriptor, "analysis.events.page.v1.events[]")
    }

    @Test fun windowResult() {
        val s = schema("window_result.v1")
        check(s, WindowResult.serializer().descriptor, "window_result.v1")
        check(sub(s, "events", "[]"), WindowEvent.serializer().descriptor, "window_result.v1.events[]")
        check(sub(s, "quality"), WindowQuality.serializer().descriptor, "window_result.v1.quality")
        val ballItems = sub(s, "ball", "[]")["prefixItems"]!!.jsonArray
        assertThat(ballItems).hasSize(5) // [t_ms, x_px, y_px, confidence|null, state]
    }

    @Test fun analysisGetAndCreate() {
        check(schema("analysis.get.response.v1"), AnalysisGetResponse.serializer().descriptor, "analysis.get.response.v1")
        check(schema("analysis.create.response.v1"), AnalysisCreateResponse.serializer().descriptor, "analysis.create.response.v1")
    }

    /** The overview reads a subset of match.v1: names must exist, required ⇒ non-optional. */
    @Test fun matchSubset() {
        val m = schema("match.v1")
        check(m, MatchRecord.serializer().descriptor, "match.v1", subset = true)
        check(sub(m, "ruleset"), MatchRuleset.serializer().descriptor, "match.v1.ruleset")
        check(sub(m, "players", "[]"), MatchPlayer.serializer().descriptor, "match.v1.players[]")
        check(sub(m, "calibration"), MatchCalibration.serializer().descriptor, "match.v1.calibration")
        check(sub(m, "analysis"), MatchAnalysis.serializer().descriptor, "match.v1.analysis")
        check(sub(m, "analysis", "artifacts"), MatchArtifacts.serializer().descriptor, "match.v1.analysis.artifacts")
        check(sub(m, "capabilities"), MatchCapabilities.serializer().descriptor, "match.v1.capabilities")
        check(sub(m, "capabilities", "ball"), MatchCapability.serializer().descriptor, "match.v1.capabilities.ball")
        check(sub(m, "rallies", "[]"), MatchRally.serializer().descriptor, "match.v1.rallies[]")
        check(sub(m, "shots", "[]"), MatchShot.serializer().descriptor, "match.v1.shots[]", subset = true)
        check(sub(m, "shots", "[]", "chosen"), MatchShotChosen.serializer().descriptor, "match.v1.shots[].chosen")
    }

    /** The fenced JSON examples in contracts.md decode with these readers. */
    @Test fun contractExamplesDecode() {
        val md = File(repo, "docs/plan/contracts.md").readText().replace("\r\n", "\n")
        val blocks = Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(md).map { it.groupValues[1] }.toList()
        fun <T> decode(ser: KSerializer<T>, marker: String): T =
            Contracts.json.decodeFromString(ser, blocks.first { marker in it })

        val w = decode(WindowResult.serializer(), "\"schema\": \"window_result.v1\"")
        assertThat(w.ballRows().map { it.state }).containsExactly("observed", "inferred").inOrder()
        assertThat(w.ballRows()[1].confidence).isNull()

        val e = decode(AnalysisEvent.serializer(), "\"type\": \"window.completed\"")
        assertThat(e.emitRangeUs).containsExactly(12_000_000L, 18_000_000L).inOrder()
        assertThat(e.engineVersion).isEqualTo("0.3.0")

        val m = decode(MatchRecord.serializer(), "\"schema\": \"match.v1\"")
        assertThat(m.rallies.single().ballCoverage).isEqualTo(0.61)
        assertThat(m.shots.single().chosen.landingXyM).containsExactly(3.0, 9.6).inOrder()
        assertThat(m.analysis.engineVersion).isEqualTo("0.3.0")

        val hint = Contracts.json.decodeFromString(RealtimeHint.serializer(),
            Regex("""\{"schema_version": 1, "analysis_id": "a_…", "latest_seq": 13\}""").find(md)!!.value)
        assertThat(hint.latestSeq).isEqualTo(13)
    }
}
