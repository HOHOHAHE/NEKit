package nekit.Config

import java.io.File
import java.io.IOException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import nekit.Rule.Rule
import nekit.Rule.RuleManager
import nekit.Config.RuleParser
import nekit.GeoIP.GeoIP // Corrected import for GeoIP
import nekit.Config.ConfigurationException // Import the new ConfigurationException

// YamlNode typealias is no longer needed with Jackson.
// typealias YamlRootNode = Map<String, Any>
// typealias YamlNode = Any // Can be Map, List, String, Int, etc.

// Removed ConfigurationException definition from here.

// --- Placeholders for dependent classes/objects ---
// These should be defined in their respective modules/files.

import nekit.Socket.AdapterSocket.Factory.AdapterFactoryManager // Corrected import

// Removed placeholder Rule and RuleManager, as they are now imported from Rule package.
// Removed placeholder RuleParser, as it's now imported from Config package.

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
    open fun loadConfigFromString(fromConfigString: String) { // Renamed to avoid overload conflict
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
    open fun load(fromConfigFile: String) { // This load method remains
        val configString = try {
            File(fromConfigFile).readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("Failed to read config file: $fromConfigFile", e)
        }
        loadConfigFromString(configString) // Call the renamed method
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
