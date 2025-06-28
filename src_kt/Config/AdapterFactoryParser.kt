package Config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.LoggerFactory
import Utils.HTTPAuthentication
import Crypto.CryptoAlgorithm
import Config.ConfigurationException.AdapterParsingException // Corrected import
import Config.ConfigurationException // Import the base ConfigurationException
import Config.getOptString
import Config.getOptInt
import Config.getOptBool
import Config.getReqString
import Config.getReqInt
import Config.getStringOrIntString
import Config.getReqStringOrIntString
import Config.getOptStringArray

// No need for ObjectNode explicitly if using JsonNode as parameter type and then checking nodeType or using `get`

// YamlNode typealias is no longer needed.

// --- Placeholder for ConfigurationParserError ---
// Moved to ConfigurationException.kt

import Socket.AdapterSocket.Factory.AdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.AdapterFactoryManager // Corrected import
import Socket.AdapterSocket.Factory.DirectAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.HTTPAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.SecureHTTPAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.SOCKS5AdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.RejectAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.ShadowsocksAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.SpeedAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.ServerAdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.HTTPAuthenticationAdapterFactory // Corrected import
import Socket.AdapterSocket.Shadowsocks.ShadowsocksAdapterNested // Corrected import

// --- Placeholders for AdapterFactory and related classes ---
// Moved to Socket.AdapterSocket.Factory package.

// For ServerAdapterFactory and its specific types
// Moved to Socket.AdapterSocket.Factory package.

// Placeholders for Shadowsocks-specific components
// Moved to Socket.AdapterSocket.Shadowsocks package.

// Assuming HTTPAuthentication.kt and CryptoAlgorithm.kt are translated and available
// data class HTTPAuthentication(val username: String, val password: String) // From Utils
// enum class CryptoAlgorithm { /* ... */ } // From Crypto

// --- Helper extensions for parsing JsonNode ---
// Moved to ConfigExtensions.kt


object AdapterFactoryParser {
    private val logger = LoggerFactory.getLogger(AdapterFactoryParser::class.java)

    @Throws(ConfigurationException::class)
    fun parseAdapterFactoryManager(adapterConfigsNode: JsonNode): AdapterFactoryManager {
        if (!adapterConfigsNode.isArray) {
            throw AdapterParsingException("Top-level adapter configuration must be an array.")
        }
        val factoryDict: MutableMap<String, AdapterFactory> = mutableMapOf()
        factoryDict["direct"] = DirectAdapterFactory() // Default direct adapter

        for (adapterConfig in adapterConfigsNode.elements()) { // Iterate over ArrayNode
            if (!adapterConfig.isObject) {
                logger.warn("Skipping non-object entry in adapter configuration list.")
                continue
            }
            val id = adapterConfig.getStringOrIntString("id") // Use new helper
                ?: throw ConfigurationException.AdapterIDMissingException()

            val type = adapterConfig.getOptString("type")?.lowercase() // Use new helper
                ?: throw ConfigurationException.AdapterTypeMissingException()

            logger.debug("Parsing adapter id: {}, type: {}", id, type)

            factoryDict[id] = when (type) {
                "speed" -> parseSpeedAdapterFactory(adapterConfig, factoryDict)
                "http" -> parseServerAdapterFactory(adapterConfig, HTTPAdapterFactory())
                "shttp" -> parseServerAdapterFactory(adapterConfig, SecureHTTPAdapterFactory())
                "ss" -> parseShadowsocksAdapterFactory(adapterConfig)
                "socks5" -> parseSOCKS5AdapterFactory(adapterConfig)
                "reject" -> parseRejectAdapterFactory(adapterConfig)
                else -> throw ConfigurationException.UnknownAdapterTypeException(type) //("Unknown adapter type: $type for id: $id")
            }
        }
        return AdapterFactoryManager(factoryDict.toMap())
    }

    @Throws(ConfigurationException::class)
    private fun parseServerAdapterFactory(
        config: JsonNode, // Changed to JsonNode
        type: HTTPAuthenticationAdapterFactory
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
        return type
    }

    @Throws(ConfigurationException::class)
    private fun parseSOCKS5AdapterFactory(config: JsonNode): SOCKS5AdapterFactory {
        val id = config.getOptString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        return SOCKS5AdapterFactory(host, port)
    }

    @Throws(ConfigurationException::class)
    private fun parseShadowsocksAdapterFactory(config: JsonNode): ShadowsocksAdapterFactory {
        val id = config.getReqStringOrIntString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        val encryptMethod = config.getReqString("method", adapterId = id)

        val algorithm = CryptoAlgorithm.values().find { it.rawValue.equals(encryptMethod, ignoreCase = true) }
            ?: throw AdapterParsingException("Encryption method $encryptMethod is not supported for Shadowsocks adapter $id.")

        val password = config.getReqStringOrIntString("password", adapterId = id)

        if (config.getOptString("ota") != null) {
            throw AdapterParsingException("Do not use \"ota: true\" for $id, use \"protocol: verify_sha1\" instead.")
        }

        val proto = config.getOptString("obfs")?.lowercase() ?: "origin"
        val stream = config.getOptString("protocol")?.lowercase() ?: "origin"

        val protocolObfuscaterFactory: ShadowsocksAdapterNested.ProtocolObfuscaterFactory = when (proto) {
            "origin" -> ShadowsocksAdapterNested.OriginProtocolObfuscaterFactoryPlaceholder()
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
                ShadowsocksAdapterNested.HTTPProtocolObfuscaterFactory(headerMethod, headerHosts, customHeader)
            }
            "tls1.2_ticket_auth" -> {
                var headerHosts = listOf(host)
                config.getOptString("obfs_param")?.let { param ->
                    headerHosts = param.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                ShadowsocksAdapterNested.TLSProtocolObfuscaterFactory(headerHosts)
            }
            else -> throw AdapterParsingException("obfs \"$proto\" is not supported for $id")
        }

        val streamObfuscaterFactory: ShadowsocksAdapterNested.StreamObfuscaterFactory = when (stream) {
            "origin" -> ShadowsocksAdapterNested.OriginStreamObfuscaterFactoryPlaceholder()
            "verify_sha1" -> ShadowsocksAdapterNested.OTAStreamObfuscaterFactory()
            else -> throw AdapterParsingException("protocol \"$stream\" is not supported for $id")
        }

        val cryptoFactory = ShadowsocksAdapterNested.CryptoStreamProcessorFactoryPlaceholder(password, algorithm)

        return ShadowsocksAdapterFactory(host, port, protocolObfuscaterFactory, cryptoFactory, streamObfuscaterFactory)
    }

    @Throws(ConfigurationException::class)
    private fun parseSpeedAdapterFactory(config: JsonNode, factoryDict: Map<String, AdapterFactory>): SpeedAdapterFactory {
        val factories = mutableListOf<Pair<AdapterFactory, Int>>()
        val adaptersNode = config.get("adapters")?.takeIf { it.isArray }
            ?: throw AdapterParsingException("Speed Adapter ${config.getStringOrIntString("id")} should specify a list of adapters (adapters).")

        for (adapterNode in adaptersNode.elements()) {
             if (!adapterNode.isObject) {
                logger.warn("Skipping non-object entry in speed adapter's adapter list for id: {}", config.getOptString("id"))
                continue
            }
            val id = adapterNode.getReqString("id", adapterId = config.getOptString("id") + " (speed child)")
            val factory = factoryDict[id]
                ?: throw AdapterParsingException("Unknown adapter id \"$id\" in Speed Adapter.")
            val delay = adapterNode.getReqInt("delay", adapterId = id)

            factories.add(Pair(factory, delay))
        }
        val speedAdapter = SpeedAdapterFactory()
        speedAdapter.adapterFactories = factories
        return speedAdapter
    }

    @Throws(ConfigurationException::class)
    private fun parseRejectAdapterFactory(config: JsonNode): RejectAdapterFactory {
        val id = config.getOptString("id")
        val delay = config.getReqInt("delay", adapterId = id)
        return RejectAdapterFactory(delay)
    }
}
