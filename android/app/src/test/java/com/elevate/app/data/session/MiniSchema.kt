package com.elevate.app.data.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The subset of JSON Schema 2020-12 that `packages/contracts/schemas/` uses (type, properties,
 * required, additionalProperties:false, enum, const, pattern, anyOf, items, prefixItems,
 * min/maxItems, minimum, maximum). Returns every violation with its path; empty = valid.
 */
object MiniSchema {
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val errs = mutableListOf<String>()
        schema["anyOf"]?.jsonArray?.let { alts ->
            if (alts.none { validate(it.jsonObject, value, path).isEmpty() }) errs += "$path: matches no anyOf branch"
            return errs
        }
        schema["const"]?.let { if (it != value) errs += "$path: expected const $it, got $value" }
        schema["enum"]?.jsonArray?.let { if (value !in it) errs += "$path: $value not in $it" }
        when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> {
                val o = value as? JsonObject ?: return listOf("$path: expected object")
                val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                schema["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in o) errs += "$path: missing required ${r.jsonPrimitive.content}" }
                if (schema["additionalProperties"] == JsonPrimitive(false)) {
                    (o.keys - props.keys).forEach { errs += "$path: unexpected property $it" }
                }
                for ((k, v) in o) props[k]?.let { errs += validate(it.jsonObject, v, "$path.$k") }
            }
            "array" -> {
                val a = value as? JsonArray ?: return listOf("$path: expected array")
                schema["minItems"]?.jsonPrimitive?.longOrNull?.let { if (a.size < it) errs += "$path: fewer than $it items" }
                schema["maxItems"]?.jsonPrimitive?.longOrNull?.let { if (a.size > it) errs += "$path: more than $it items" }
                val prefix = schema["prefixItems"]?.jsonArray
                a.forEachIndexed { i, v ->
                    val s = prefix?.getOrNull(i) ?: schema["items"]
                    when {
                        s == null -> Unit
                        s == JsonPrimitive(false) -> errs += "$path[$i]: no items allowed here"
                        s is JsonObject -> errs += validate(s, v, "$path[$i]")
                    }
                }
            }
            "string" -> {
                val p = value as? JsonPrimitive
                if (p == null || !p.isString) return listOf("$path: expected string")
                schema["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(p.content)) errs += "$path: '${p.content}' does not match $it" }
            }
            "integer" -> {
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return listOf("$path: expected integer, got $value")
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (n < it) errs += "$path: $n < $it" }
                schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (n > it) errs += "$path: $n > $it" }
            }
            "number" -> {
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return listOf("$path: expected number")
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (n < it) errs += "$path: $n < $it" }
            }
            "boolean" -> if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) errs += "$path: expected boolean"
            "null" -> if (value != JsonNull) errs += "$path: expected null"
        }
        return errs
    }
}
