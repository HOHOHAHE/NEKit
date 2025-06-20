// TODO: Replace Yaml parsing with a Kotlin YAML library (e.g., Jackson-YAML, SnakeYAML).
// For now, 'YamlNode' is used as a placeholder type, assumed to be Map<String, Any>.
typealias YamlNode = Map<String, Any>

// --- Placeholder for ConfigurationParserError ---
// Should be defined in its own file or a common error file.
sealed class ConfigurationParserError(message: String) : Exception(message) {
    object NoAdapterDefined : ConfigurationParserError("No adapter defined in configuration.")
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


// Helper extensions for parsing YamlNode (Map<String, Any>)
// TODO: Replace these with actual YAML library access methods.
@Suppress("UNCHECKED_CAST")
fun YamlNode.getOptList(key: String): List<YamlNode>? = this[key] as? List<YamlNode>

fun YamlNode.getOptString(key: String): String? = this[key] as? String

fun YamlNode.getOptInt(key: String): Int? = (this[key] as? Number)?.toInt()

fun YamlNode.getOptBool(key: String): Boolean? = this[key] as? Boolean

fun YamlNode.getStringOrIntString(key: String): String? {
    return when (val value = this[key]) {
        is String -> value
        is Int -> value.toString()
        is Long -> value.toString()
        // Add other numeric types if necessary
        else -> null
    }
}


object AdapterFactoryParser {

    // Swift: static func parseAdapterFactoryManager(_ config: Yaml) throws -> AdapterFactoryManager
    // Yaml here is the root array of adapter configs, not the entire Yaml doc.
    @Throws(ConfigurationParserError::class)
    fun parseAdapterFactoryManager(adapterConfigsList: List<YamlNode>): AdapterFactoryManager {
        val factoryDict: MutableMap<String, AdapterFactory> = mutableMapOf()
        factoryDict["direct"] = DirectAdapterFactory() // Default direct adapter

        for (adapterConfig in adapterConfigsList) {
            val id = adapterConfig.getStringOrIntString("id")
                ?: throw ConfigurationParserError.AdapterIDMissing

            val type = adapterConfig.getOptString("type")?.lowercase()
                ?: throw ConfigurationParserError.AdapterTypeMissing

            factoryDict[id] = when (type) {
                "speed" -> parseSpeedAdapterFactory(adapterConfig, factoryDict)
                "http" -> parseServerAdapterFactory(adapterConfig, HTTPAdapterFactory)
                "shttp" -> parseServerAdapterFactory(adapterConfig, SecureHTTPAdapterFactory)
                "ss" -> parseShadowsocksAdapterFactory(adapterConfig)
                "socks5" -> parseSOCKS5AdapterFactory(adapterConfig)
                "reject" -> parseRejectAdapterFactory(adapterConfig)
                else -> throw ConfigurationParserError.AdapterTypeUnknown
            }
        }
        return AdapterFactoryManager(factoryDict.toMap())
    }

    @Throws(ConfigurationParserError::class)
    private fun parseServerAdapterFactory(
        config: YamlNode,
        type: HTTPAuthenticationAdapterFactoryType // Using the interface to pass factory type
    ): ServerAdapterFactory {
        val host = config.getOptString("host")
            ?: throw ConfigurationParserError.AdapterParsingError("Host (host) is required for ${config.getOptString("id")}.")
        val port = config.getOptInt("port")
            ?: throw ConfigurationParserError.AdapterParsingError("Port (port) is required for ${config.getOptString("id")}.")

        var authentication: HTTPAuthentication? = null
        if (config.getOptBool("auth") == true) {
            val username = config.getStringOrIntString("username")
                ?: throw ConfigurationParserError.AdapterParsingError("Username (username) is required when auth is true for ${config.getOptString("id")}.")
            val password = config.getStringOrIntString("password")
                ?: throw ConfigurationParserError.AdapterParsingError("Password (password) is required when auth is true for ${config.getOptString("id")}.")
            authentication = HTTPAuthentication(username, password) // Assumes HTTPAuthentication is available
        }
        return type.create(host, port, authentication)
    }

    @Throws(ConfigurationParserError::class)
    private fun parseSOCKS5AdapterFactory(config: YamlNode): SOCKS5AdapterFactory {
        val host = config.getOptString("host")
            ?: throw ConfigurationParserError.AdapterParsingError("Host (host) is required for SOCKS5 adapter ${config.getOptString("id")}.")
        val port = config.getOptInt("port")
            ?: throw ConfigurationParserError.AdapterParsingError("Port (port) is required for SOCKS5 adapter ${config.getOptString("id")}.")
        return SOCKS5AdapterFactory(host, port)
    }

    @Throws(ConfigurationParserError::class)
    private fun parseShadowsocksAdapterFactory(config: YamlNode): ShadowsocksAdapterFactory {
        val id = config.getStringOrIntString("id") ?: "Unknown ID"
        val host = config.getOptString("host")
            ?: throw ConfigurationParserError.AdapterParsingError("Host (host) is required for Shadowsocks adapter $id.")
        val port = config.getOptInt("port")
            ?: throw ConfigurationParserError.AdapterParsingError("Port (port) is required for Shadowsocks adapter $id.")
        val encryptMethod = config.getOptString("method")
            ?: throw ConfigurationParserError.AdapterParsingError("Encryption method (method) is required for Shadowsocks adapter $id.")

        val algorithm = CryptoAlgorithm.values().find { it.rawValue.equals(encryptMethod, ignoreCase = true) }
            ?: throw ConfigurationParserError.AdapterParsingError("Encryption method $encryptMethod is not supported for Shadowsocks adapter $id.")

        val password = config.getStringOrIntString("password")
            ?: throw ConfigurationParserError.AdapterParsingError("Password (password) is required for Shadowsocks adapter $id.")

        if (config.getOptString("ota") != null) { // Swift code had `if let _ = ...`
            throw ConfigurationParserError.AdapterParsingError("Do not use \"ota: true\" for $id, use \"protocol: verify_sha1\" instead.")
        }

        val proto = config.getOptString("obfs")?.lowercase() ?: "origin"
        val stream = config.getOptString("protocol")?.lowercase() ?: "origin"

        val protocolObfuscaterFactory: ShadowsocksAdapter.ProtocolObfuscater.Factory = when (proto) {
            "origin" -> ShadowsocksAdapter.ProtocolObfuscater.OriginProtocolObfuscater.Factory()
            "http_simple" -> {
                var headerHosts = listOf(host)
                var customHeader: String? = null
                val headerMethod = "GET" // Default in Swift

                config.getOptString("obfs_param")?.let { param ->
                    val params = param.split('#', limit = 2)
                    if (params.isNotEmpty()) {
                        headerHosts = params[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (params.size > 1) {
                            customHeader = params[1].replace("\\n", "\r\n") // Original Swift also replaced literal \n
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
    private fun parseSpeedAdapterFactory(config: YamlNode, factoryDict: Map<String, AdapterFactory>): SpeedAdapterFactory {
        val factories = mutableListOf<Pair<AdapterFactory, Int>>()
        val adaptersNode = config.getOptList("adapters")
            ?: throw ConfigurationParserError.AdapterParsingError("Speed Adapter ${config.getStringOrIntString("id")} should specify a set of adapters (adapters).")

        for (adapterNode in adaptersNode) {
            val id = adapterNode.getOptString("id")
                ?: throw ConfigurationParserError.AdapterParsingError("An adapter id (id) is required within Speed Adapter.")
            val factory = factoryDict[id]
                ?: throw ConfigurationParserError.AdapterParsingError("Unknown adapter id \"$id\" in Speed Adapter.")
            val delay = adapterNode.getOptInt("delay")
                ?: throw ConfigurationParserError.AdapterParsingError("Each adapter in Speed Adapter must specify a delay in milliseconds.")

            factories.add(Pair(factory, delay))
        }
        val speedAdapter = SpeedAdapterFactory()
        speedAdapter.adapterFactories = factories
        return speedAdapter
    }

    @Throws(ConfigurationParserError::class)
    private fun parseRejectAdapterFactory(config: YamlNode): RejectAdapterFactory {
        val delay = config.getOptInt("delay")
            ?: throw ConfigurationParserError.AdapterParsingError("Reject adapter ${config.getStringOrIntString("id")} must specify a delay in millisecond.")
        return RejectAdapterFactory(delay)
    }
}
