package Config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode // ArrayNode might be used in these extensions

// --- Helper extensions for parsing JsonNode ---
fun JsonNode.getOptString(key: String): String? = this.get(key)?.takeIf { it.isTextual }?.asText()
fun JsonNode.getOptInt(key: String): Int? = this.get(key)?.takeIf { it.isInt }?.asInt()
fun JsonNode.getOptBool(key: String): Boolean? = this.get(key)?.takeIf { it.isBoolean }?.asBoolean()

fun JsonNode.getReqString(key: String, adapterId: String? = "Unknown"): String =
    this.get(key)?.takeIf { it.isTextual }?.asText()
        ?: throw ConfigurationParserError.AdapterParsingError("\"$key\" (string) is required for adapter \"${adapterId ?: this.getOptString("id") ?: "Unnamed"}\".")

fun JsonNode.getReqInt(key: String, adapterId: String? = "Unknown"): Int =
    this.get(key)?.takeIf { it.isInt }?.asInt()
        ?: throw ConfigurationParserError.AdapterParsingError("\"$key\" (integer) is required for adapter \"${adapterId ?: this.getOptString("id") ?: "Unnamed"}\".")

// Keep getStringOrIntString as its logic is specific for mixed type fields
fun JsonNode.getStringOrIntString(key: String): String? {
    val node = this.get(key)
    return when {
        node == null || node.isNull -> null
        node.isTextual -> node.asText()
        node.isInt || node.isLong || node.isBigInteger -> node.numberValue().toString()
        else -> null
    }
}

fun JsonNode.getReqStringOrIntString(key: String, adapterId: String? = "Unknown"): String =
    this.getStringOrIntString(key)
        ?: throw ConfigurationParserError.AdapterParsingError("\"$key\" (string or integer) is required for adapter \"${adapterId ?: this.getOptString("id") ?: "Unnamed"}\".")

fun JsonNode.getOptStringArray(key: String): List<String>? =
    this.get(key)?.takeIf { it.isArray }?.mapNotNull { it.takeIf {el -> el.isTextual}?.asText() }