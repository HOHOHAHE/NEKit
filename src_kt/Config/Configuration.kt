import java.io.File
import java.io.IOException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory // Already present via RuleParser, but good to ensure for Configuration class itself

// YamlRootNode and YamlNode typealiases are no longer needed with Jackson.
// typealias YamlRootNode = Map<String, Any>
// typealias YamlNode = Any // Can be Map, List, String, Int, etc.

// --- ConfigurationParserError Definitions ---
// Ideally, these would be in a separate file like "ConfigurationErrors.kt"
sealed class ConfigurationException(message: String) : Exception(message) {
    class InvalidYamlFileException(message: String = "Invalid YAML file content.") : ConfigurationException(message)
    class NoRuleDefinedException(message: String = "No rule defined in configuration.") : ConfigurationException(message)
    class RuleTypeMissingException(message: String = "Rule type is missing.") : ConfigurationException(message)
    class UnknownRuleTypeException(typeName: String) : ConfigurationException("Unknown rule type: $typeName")
    class RuleParsingException(errorInfo: String) : ConfigurationException("Rule parsing error: $errorInfo")
    class NoAdapterDefinedException(message: String = "No adapter defined in configuration.") : ConfigurationException(message)
    class AdapterIDMissingException(message: String = "Adapter ID is missing.") : ConfigurationException(message)
    class AdapterTypeMissingException(message: String = "Adapter type is missing.") : ConfigurationException(message)
    class UnknownAdapterTypeException(typeName: String) : ConfigurationException("Unknown adapter type: $typeName")
    class AdapterParsingException(errorInfo: String) : ConfigurationException("Adapter parsing error: $errorInfo")
}

// --- Placeholders for dependent classes/objects ---
// These should be defined in their respective modules/files.

// From AdapterFactoryParser.kt context (or its own file)
// interface AdapterFactory
// class AdapterFactoryManager(val factoryDict: Map<String, AdapterFactory>)

// New Placeholders
interface Rule // Base for rules
class RuleManager(val rules: List<Rule>) // Simplified

object RuleParser { // Placeholder for RuleParser.swift
    private val logger = LoggerFactory.getLogger(RuleParser::class.java)
    @Throws(ConfigurationException::class)
    fun parseRuleManager(configSection: JsonNode?, adapterFactoryManager: AdapterFactoryManager): RuleManager {
        // TODO: Implement actual RuleParser logic
        logger.info("TODO: RuleParser.parseRuleManager called with configSection (type: JsonNode)")
        if (configSection == null || configSection.isNull || !configSection.isArray) {
             // Allow empty rule section
            if(configSection != null && !configSection.isNull && !configSection.isMissingNode) {
                 if (!configSection.isArray) throw ConfigurationException.RuleParsingException("Rule section must be an array.")
            }
             logger.info("No rules defined or rule section is not an array, creating empty RuleManager.")
             return RuleManager(emptyList())
        }
        // Actual parsing logic will iterate through configSection array.
        return RuleManager(emptyList()) // Dummy implementation
    }
}

// YamlNode helper extensions are no longer needed here.
// JsonNode helpers will be defined in AdapterFactoryParser and RuleParser.

/**
 * The configuration file parser.
 * Note: It is not recommended to use this class in production app. This is merely used as a helper.
 */
open class Configuration {
    private val logger = LoggerFactory.getLogger(Configuration::class.java)
    var adapterFactoryManager: AdapterFactoryManager? = null
    open var proxyPort: Int? = null
    open var ruleManager: RuleManager? = null
    var geoIPDatabasePath: String? = null // Added GeoIP database path property

    constructor()

    @Throws(ConfigurationException::class, IOException::class)
    open fun load(fromConfigString: String) {
        val mapper = ObjectMapper(YAMLFactory())
        val configNode: JsonNode
        try {
            configNode = mapper.readTree(fromConfigString)
        } catch (e: JsonProcessingException) {
            throw ConfigurationException.InvalidYamlFileException("Failed to parse YAML string: ${e.message}")
        } catch (e: IOException) { // Catch other IO exceptions during read
            throw IOException("Error reading YAML string: ${e.message}", e)
        }

        if (configNode == null || configNode.isNull) {
            throw ConfigurationException.InvalidYamlFileException("Parsed YAML is null or empty.")
        }

        loadConfigProperties(configNode)

        val adapterConfigNode = configNode.get("adapter")
            ?: throw ConfigurationException.NoAdapterDefinedException("Adapter section missing in configuration.")
        if (!adapterConfigNode.isArray) {
            throw ConfigurationException.InvalidYamlFileException("Adapter configuration must be a list.")
        }
        // AdapterFactoryParser.parseAdapterFactoryManager will expect JsonNode (specifically an ArrayNode)
        adapterFactoryManager = AdapterFactoryParser.parseAdapterFactoryManager(adapterConfigNode)

        val ruleConfigNode = configNode.get("rule") // Optional
        ruleManager = RuleParser.parseRuleManager(ruleConfigNode, adapterFactoryManager!!)

        // Initialize GeoIP database if path is configured
        if (!geoIPDatabasePath.isNullOrBlank()) {
            logger.info("GeoIP database path found in configuration: {}", geoIPDatabasePath)
            GeoIP.initialize(geoIPDatabasePath!!)
        } else {
            logger.info("No GeoIP database path found in configuration. GeoIP lookups will be disabled.")
        }
    }

    @Throws(ConfigurationException::class, IOException::class)
    open fun load(fromConfigFile: String) {
        val configString = try {
            File(fromConfigFile).readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("Failed to read config file: $fromConfigFile", e)
        }
        load(fromConfigString)
    }

    private fun loadConfigProperties(config: JsonNode) {
        // Example of accessing a top-level property "port"
        proxyPort = config.get("port")?.asInt()
                                                // JsonNode.get(key) returns null if key not found
                                                // JsonNode?.asInt() returns null if node is null or not an int

        // Parse GeoIP database path
        geoIPDatabasePath = config.get("geoip-database-path")?.asText()
    }
}

// stringOrIntString helper is no longer needed here, JsonNode.asText() handles numbers to string.
