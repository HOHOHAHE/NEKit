import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
// No need for ObjectNode explicitly if using JsonNode as parameter type and then checking nodeType or using `get`

// YamlNode typealias is no longer needed.

// --- Placeholder for ConfigurationParserError ---
// Should be defined in its own file or a common error file.
sealed class ConfigurationParserError(message: String) : Exception(message) {
    // object NoAdapterDefined : ConfigurationParserError("No adapter defined in configuration.") // Defined in Configuration.kt
    object AdapterIDMissing : ConfigurationParserError("Adapter ID is missing.")
    object AdapterTypeMissing : ConfigurationParserError("Adapter type is missing.")
    object AdapterTypeUnknown : ConfigurationParserError("Unknown adapter type encountered.")
    class AdapterParsingError(errorInfo: String) : ConfigurationParserError("Adapter parsing error: $errorInfo")
}

// --- Placeholders for AdapterFactory and related classes ---
// These should be defined in their respective modules/files.
interface AdapterFactory // Base interface
class DirectAdapterFactory : AdapterFactory
class AdapterFactoryManager(val factoryDict: Map<String, AdapterFactory>)

// For ServerAdapterFactory and its specific types
open class ServerAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val auth: HTTPAuthentication? // Assuming HTTPAuthentication.kt is available
) : AdapterFactory

interface HTTPAuthenticationAdapterFactoryType { // To mimic type: HTTPAuthenticationAdapterFactory.Type
    fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): ServerAdapterFactory
}


class HTTPAdapterFactory(
    serverHost: String,
    serverPort: Int,
    auth: HTTPAuthentication?
) : ServerAdapterFactory(serverHost, serverPort, auth), HTTPAuthenticationAdapterFactoryType {
    override fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): ServerAdapterFactory {
        return HTTPAdapterFactory(serverHost, serverPort, auth)
    }
    companion object : HTTPAuthenticationAdapterFactoryType {
        override fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): ServerAdapterFactory {
            return HTTPAdapterFactory(serverHost, serverPort, auth)
        }
    }
}


class SecureHTTPAdapterFactory(
    serverHost: String,
    serverPort: Int,
    auth: HTTPAuthentication?
) : ServerAdapterFactory(serverHost, serverPort, auth), HTTPAuthenticationAdapterFactoryType {
     override fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): ServerAdapterFactory {
        return SecureHTTPAdapterFactory(serverHost, serverPort, auth)
    }
    companion object : HTTPAuthenticationAdapterFactoryType {
         override fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): ServerAdapterFactory {
            return SecureHTTPAdapterFactory(serverHost, serverPort, auth)
        }
    }
}


class SOCKS5AdapterFactory(
    val serverHost: String,
    val serverPort: Int
    // Assuming no auth for SOCKS5 based on Swift, add if necessary
) : AdapterFactory

class RejectAdapterFactory(val delay: Int) : AdapterFactory

// Placeholders for Shadowsocks-specific components
object ShadowsocksAdapter {
    object ProtocolObfuscater {
        interface Factory : AdapterFactory // Or some other base if not an AdapterFactory itself
        class OriginProtocolObfuscater { class Factory : ProtocolObfuscater.Factory }
        class HTTPProtocolObfuscater { class Factory(val method: String, val hosts: List<String>, val customHeader: String?) : ProtocolObfuscater.Factory }
        class TLSProtocolObfuscater { class Factory(val hosts: List<String>) : ProtocolObfuscater.Factory }
    }
    object StreamObfuscater {
        interface Factory : AdapterFactory // Or some other base
        class OriginStreamObfuscater { class Factory : StreamObfuscater.Factory }
        class OTAStreamObfuscater { class Factory : StreamObfuscater.Factory }
    }
    object CryptoStreamProcessor {
        class Factory(val password: String, val algorithm: CryptoAlgorithm) : AdapterFactory // Or some other base
    }
}

class ShadowsocksAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val protocolObfuscaterFactory: ShadowsocksAdapter.ProtocolObfuscater.Factory,
    val cryptorFactory: ShadowsocksAdapter.CryptoStreamProcessor.Factory,
    val streamObfuscaterFactory: ShadowsocksAdapter.StreamObfuscater.Factory
) : AdapterFactory

class SpeedAdapterFactory : AdapterFactory {
    var adapterFactories: List<Pair<AdapterFactory, Int>> = emptyList()
}

// Assuming HTTPAuthentication.kt and CryptoAlgorithm.kt are translated and available
// data class HTTPAuthentication(val username: String, val password: String) // From Utils
// enum class CryptoAlgorithm { /* ... */ } // From Crypto

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


object AdapterFactoryParser {
    private val logger = LoggerFactory.getLogger(AdapterFactoryParser::class.java)

    @Throws(ConfigurationParserError::class)
    fun parseAdapterFactoryManager(adapterConfigsNode: JsonNode): AdapterFactoryManager {
        if (!adapterConfigsNode.isArray) {
            throw ConfigurationParserError.AdapterParsingError("Top-level adapter configuration must be an array.")
        }
        val factoryDict: MutableMap<String, AdapterFactory> = mutableMapOf()
        factoryDict["direct"] = DirectAdapterFactory() // Default direct adapter

        for (adapterConfig in adapterConfigsNode.elements()) { // Iterate over ArrayNode
            if (!adapterConfig.isObject) {
                logger.warn("Skipping non-object entry in adapter configuration list.")
                continue
            }
            val id = adapterConfig.getStringOrIntString("id") // Use new helper
                ?: throw ConfigurationParserError.AdapterIDMissing

            val type = adapterConfig.getOptString("type")?.lowercase() // Use new helper
                ?: throw ConfigurationParserError.AdapterTypeMissing

            logger.debug("Parsing adapter id: {}, type: {}", id, type)

            factoryDict[id] = when (type) {
                "speed" -> parseSpeedAdapterFactory(adapterConfig, factoryDict)
                "http" -> parseServerAdapterFactory(adapterConfig, HTTPAdapterFactory)
                "shttp" -> parseServerAdapterFactory(adapterConfig, SecureHTTPAdapterFactory)
                "ss" -> parseShadowsocksAdapterFactory(adapterConfig)
                "socks5" -> parseSOCKS5AdapterFactory(adapterConfig)
                "reject" -> parseRejectAdapterFactory(adapterConfig)
                else -> throw ConfigurationParserError.AdapterTypeUnknown //("Unknown adapter type: $type for id: $id")
            }
        }
        return AdapterFactoryManager(factoryDict.toMap())
    }

    @Throws(ConfigurationParserError::class)
    private fun parseServerAdapterFactory(
        config: JsonNode, // Changed to JsonNode
        type: HTTPAuthenticationAdapterFactoryType
    ): ServerAdapterFactory {
        val id = config.getOptString("id") // For error messages
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)

        var authentication: HTTPAuthentication? = null
        if (config.getOptBool("auth") == true) {
            val username = config.getReqStringOrIntString("username", adapterId = id)
            val password = config.getReqStringOrIntString("password", adapterId = id)
            authentication = HTTPAuthentication(username, password)
        }
        return type.create(host, port, authentication)
    }

    @Throws(ConfigurationParserError::class)
    private fun parseSOCKS5AdapterFactory(config: JsonNode): SOCKS5AdapterFactory {
        val id = config.getOptString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        return SOCKS5AdapterFactory(host, port)
    }

    @Throws(ConfigurationParserError::class)
    private fun parseShadowsocksAdapterFactory(config: JsonNode): ShadowsocksAdapterFactory {
        val id = config.getReqStringOrIntString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        val encryptMethod = config.getReqString("method", adapterId = id)

        val algorithm = CryptoAlgorithm.values().find { it.rawValue.equals(encryptMethod, ignoreCase = true) }
            ?: throw ConfigurationParserError.AdapterParsingError("Encryption method $encryptMethod is not supported for Shadowsocks adapter $id.")

        val password = config.getReqStringOrIntString("password", adapterId = id)

        if (config.getOptString("ota") != null) {
            throw ConfigurationParserError.AdapterParsingError("Do not use \"ota: true\" for $id, use \"protocol: verify_sha1\" instead.")
        }

        val proto = config.getOptString("obfs")?.lowercase() ?: "origin"
        val stream = config.getOptString("protocol")?.lowercase() ?: "origin"

        val protocolObfuscaterFactory: ShadowsocksAdapter.ProtocolObfuscater.Factory = when (proto) {
            "origin" -> ShadowsocksAdapter.ProtocolObfuscater.OriginProtocolObfuscater.Factory()
            "http_simple" -> {
                var headerHosts = listOf(host)
                var customHeader: String? = null
                val headerMethod = "GET" // Default

                config.getOptString("obfs_param")?.let { param ->
                    val params = param.split('#', limit = 2)
                    if (params.isNotEmpty()) {
                        headerHosts = params[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (params.size > 1) {
                            customHeader = params[1].replace("\\n", "\r\n")
                        }
                    }
                }
                ShadowsocksAdapter.ProtocolObfuscater.HTTPProtocolObfuscater.Factory(headerMethod, headerHosts, customHeader)
            }
            "tls1.2_ticket_auth" -> {
                var headerHosts = listOf(host)
                config.getOptString("obfs_param")?.let { param ->
                    headerHosts = param.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                ShadowsocksAdapter.ProtocolObfuscater.TLSProtocolObfuscater.Factory(headerHosts)
            }
            else -> throw ConfigurationParserError.AdapterParsingError("obfs \"$proto\" is not supported for $id")
        }

        val streamObfuscaterFactory: ShadowsocksAdapter.StreamObfuscater.Factory = when (stream) {
            "origin" -> ShadowsocksAdapter.StreamObfuscater.OriginStreamObfuscater.Factory()
            "verify_sha1" -> ShadowsocksAdapter.StreamObfuscater.OTAStreamObfuscater.Factory()
            else -> throw ConfigurationParserError.AdapterParsingError("protocol \"$stream\" is not supported for $id")
        }

        val cryptoFactory = ShadowsocksAdapter.CryptoStreamProcessor.Factory(password, algorithm)

        return ShadowsocksAdapterFactory(host, port, protocolObfuscaterFactory, cryptoFactory, streamObfuscaterFactory)
    }

    @Throws(ConfigurationParserError::class)
    private fun parseSpeedAdapterFactory(config: JsonNode, factoryDict: Map<String, AdapterFactory>): SpeedAdapterFactory {
        val factories = mutableListOf<Pair<AdapterFactory, Int>>()
        val adaptersNode = config.get("adapters")?.takeIf { it.isArray }
            ?: throw ConfigurationParserError.AdapterParsingError("Speed Adapter ${config.getStringOrIntString("id")} should specify a list of adapters (adapters).")

        for (adapterNode in adaptersNode.elements()) {
             if (!adapterNode.isObject) {
                logger.warn("Skipping non-object entry in speed adapter's adapter list for id: {}", config.getOptString("id"))
                continue
            }
            val id = adapterNode.getReqString("id", adapterId = config.getOptString("id") + " (speed child)")
            val factory = factoryDict[id]
                ?: throw ConfigurationParserError.AdapterParsingError("Unknown adapter id \"$id\" in Speed Adapter.")
            val delay = adapterNode.getReqInt("delay", adapterId = id)

            factories.add(Pair(factory, delay))
        }
        val speedAdapter = SpeedAdapterFactory()
        speedAdapter.adapterFactories = factories
        return speedAdapter
    }

    @Throws(ConfigurationParserError::class)
    private fun parseRejectAdapterFactory(config: JsonNode): RejectAdapterFactory {
        val id = config.getOptString("id")
        val delay = config.getReqInt("delay", adapterId = id)
        return RejectAdapterFactory(delay)
    }
}
