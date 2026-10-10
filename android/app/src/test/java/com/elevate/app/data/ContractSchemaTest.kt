package com.elevate.app.data

import com.elevate.app.data.api.AnalysisEvent
import com.elevate.app.data.api.AnalysisEventsPage
import com.elevate.app.data.api.Contracts
import com.elevate.app.data.api.ErrorBody
import com.elevate.app.data.api.ErrorEnvelope
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

    private fun check(node: JsonObject, d: SerialDescriptor, what: String) {
        val vs = variants(node)
        val allProps = vs.flatMap(::props).toSet()
        val requiredEverywhere = vs.map(::required).reduce { a, b -> a intersect b }
        val names = (0 until d.elementsCount).associateBy { d.getElementName(it) }
        assertWithMessage("$what: fields").that(names.keys).containsExactlyElementsIn(allProps)
        for (r in requiredEverywhere) {
            val i = names.getValue(r)
            assertWithMessage("$what.$r is required by the schema").that(d.isElementOptional(i) || d.getElementDescriptor(i).isNullable).isFalse()
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

        val hint = Contracts.json.decodeFromString(RealtimeHint.serializer(),
            Regex("""\{"schema_version": 1, "analysis_id": "a_…", "latest_seq": 13\}""").find(md)!!.value)
        assertThat(hint.latestSeq).isEqualTo(13)
    }
}
