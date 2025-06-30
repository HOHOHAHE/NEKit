package com.example.nekit.Config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.LoggerFactory
import com.example.nekit.Utils.HTTPAuthentication
import com.example.nekit.Crypto.CryptoAlgorithm
import com.example.nekit.Config.ConfigurationException.AdapterParsingException
import com.example.nekit.Config.ConfigurationException
import com.example.nekit.Socket.AdapterSocket.Factory.*
import com.example.nekit.Socket.AdapterSocket.Shadowsocks.ShadowsocksAdapterNested

object AdapterFactoryParser {
    private val logger = LoggerFactory.getLogger(AdapterFactoryParser::class.java)

    @Throws(ConfigurationException::class)
    fun parseAdapterFactoryManager(adapterConfigsNode: JsonNode): AdapterFactoryManager {
        if (!adapterConfigsNode.isArray) {
            throw AdapterParsingException("Top-level adapter configuration must be an array.")
        }
        val factoryDict: MutableMap<String, AdapterFactory> = mutableMapOf()
        factoryDict["direct"] = DirectAdapterFactory()

        for (adapterConfig in adapterConfigsNode.elements()) {
            if (!adapterConfig.isObject) {
                logger.warn("Skipping non-object entry in adapter configuration list.")
                continue
            }
            val id = adapterConfig.getStringOrIntString("id")
                ?: throw ConfigurationException.AdapterIDMissingException("Adapter ID is missing.")
            val type = adapterConfig.getOptString("type")?.lowercase()
                ?: throw ConfigurationException.AdapterTypeMissingException("Adapter type is missing.")

            logger.debug("Parsing adapter id: {}, type: {}", id, type)

            factoryDict[id] = when (type) {
                "speed" -> parseSpeedAdapterFactory(adapterConfig, factoryDict)
                "http" -> parseHTTPAdapterFactory(adapterConfig)
                "shttp" -> parseSecureHTTPAdapterFactory(adapterConfig)
                "ss" -> parseShadowsocksAdapterFactory(adapterConfig)
                "socks5" -> parseSOCKS5AdapterFactory(adapterConfig)
                "reject" -> parseRejectAdapterFactory(adapterConfig)
                else -> throw ConfigurationException.UnknownAdapterTypeException("Unknown adapter type: $type for id: $id")
            }
        }
        return AdapterFactoryManager(factoryDict.toMap())
    }

    @Throws(ConfigurationException::class)
    private fun parseHTTPAdapterFactory(config: JsonNode): HTTPAdapterFactory {
        val id = config.getOptString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        var authentication: HTTPAuthentication? = null
        if (config.getOptBool("auth") == true) {
            val username = config.getReqStringOrIntString("username", adapterId = id)
            val password = config.getReqStringOrIntString("password", adapterId = id)
            authentication = HTTPAuthentication(username, password)
        }
        return HTTPAdapterFactory(host, port, authentication)
    }

    @Throws(ConfigurationException::class)
    private fun parseSecureHTTPAdapterFactory(config: JsonNode): SecureHTTPAdapterFactory {
        val id = config.getOptString("id")
        val host = config.getReqString("host", adapterId = id)
        val port = config.getReqInt("port", adapterId = id)
        var authentication: HTTPAuthentication? = null
        if (config.getOptBool("auth") == true) {
            val username = config.getReqStringOrIntString("username", adapterId = id)
            val password = config.getReqStringOrIntString("password", adapterId = id)
            authentication = HTTPAuthentication(username, password)
        }
        return SecureHTTPAdapterFactory(host, port, authentication)
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
        val password = config.getReqStringOrIntString("password", adapterId = id)
        val key = password.toByteArray() // Simplified key derivation

        return ShadowsocksAdapterFactory(host, port, encryptMethod, key)
    }

    @Throws(ConfigurationException::class)
    private fun parseSpeedAdapterFactory(config: JsonNode, factoryDict: Map<String, AdapterFactory>): SpeedAdapterFactory {
        val factories = mutableListOf<AdapterFactory>()
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
            factories.add(factory)
        }
        val testUrl = config.getOptString("testUrl") ?: "http://www.google.com/generate_204"
        return SpeedAdapterFactory(factories, testUrl)
    }

    @Throws(ConfigurationException::class)
    private fun parseRejectAdapterFactory(config: JsonNode): RejectAdapterFactory {
        val id = config.getOptString("id")
        val delay = config.getReqInt("delay", adapterId = id)
        return RejectAdapterFactory(delay)
    }
}
