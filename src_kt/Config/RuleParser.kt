import java.io.File
import java.io.IOException
import com.fasterxml.jackson.databind.JsonNode
// Assuming AdapterFactoryManager, ConfigurationException are available
// from Configuration.kt or common files.
// Also assuming placeholder Rule types and AdapterFactory.

// YamlNode and its helpers are no longer needed. JsonNode helpers will be used.

// --- Placeholders for Rule types ---
interface Rule // Base interface, matches placeholder in Configuration.kt

data class CountryRule(
    val countryCode: String,
    val match: Boolean,
    val adapterFactory: AdapterFactory
) : Rule

data class AllRule(val adapterFactory: AdapterFactory) : Rule

data class DomainListRule(
    val adapterFactory: AdapterFactory,
    val criteria: List<MatchCriterion>
) : Rule {
    sealed class MatchCriterion {
        data class RegexCriterion(val regex: Regex) : MatchCriterion()
        // Potentially other types like plain string match, etc.
        // For now, only Regex based on Swift code.
    }
}

data class IPRangeListRule(
    val adapterFactory: AdapterFactory,
    val ranges: List<String> // Assuming constructor will parse these strings into IPRange objects
) : Rule {
    // Constructor would likely take these strings and convert to List<IPRange>
    // For now, just storing strings as per direct translation of parser step.
    // init { val parsedRanges = ranges.mapNotNull { IPRange.fromString(it) } /* ... */ }
}

data class DNSFailRule(val adapterFactory: AdapterFactory) : Rule

// Assuming RuleManager from Configuration.kt context is:
// class RuleManager(val rules: List<Rule>)
// The `appendDirect` property seems to be part of the original NEKit.Rule.RuleManager,
// let's keep it if it was in the Swift version.
// From Configuration.kt, the placeholder was: class RuleManager(val rules: List<Rule>)
// Let's assume the version from this file (RuleParser.swift context) is more accurate for RuleManager.
class RuleManager(val rules: List<Rule>, val appendDirect: Boolean = true) { // Default appendDirect to true
    // constructor(fromRules: List<Rule>, appendDirect: Boolean) : this(fromRules, appendDirect) // Redundant
}


// --- Helper for path expansion ---
fun expandTilde(path: String): String {
    if (path.startsWith("~")) {
        val homeDir = System.getProperty("user.home")
        if (homeDir != null) {
            return homeDir + path.substring(1)
        }
    }
    return path
}

// --- JsonNode Helper Extensions (redefined here for standalone use, or move to common file) ---
fun JsonNode.getOptString(key: String): String? = this.get(key)?.takeIf { it.isTextual }?.asText()
fun JsonNode.getOptInt(key: String): Int? = this.get(key)?.takeIf { it.isInt }?.asInt()
fun JsonNode.getOptBool(key: String): Boolean? = this.get(key)?.takeIf { it.isBoolean }?.asBoolean()

fun JsonNode.getReqString(key: String, ruleType: String? = "UnknownRule"): String =
    this.get(key)?.takeIf { it.isTextual }?.asText()
        ?: throw ConfigurationException.RuleParsingException("\"$key\" (string) is required for $ruleType rule.")

fun JsonNode.getReqBool(key: String, ruleType: String? = "UnknownRule"): Boolean =
    this.get(key)?.takeIf { it.isBoolean }?.asBoolean()
        ?: throw ConfigurationException.RuleParsingException("\"$key\" (boolean) is required for $ruleType rule.")

fun JsonNode.getStringOrIntString(key: String): String? { // Keep this specific logic
    val node = this.get(key)
    return when {
        node == null || node.isNull -> null
        node.isTextual -> node.asText()
        node.isInt || node.isLong || node.isBigInteger -> node.numberValue().toString()
        else -> null
    }
}

fun JsonNode.getReqStringOrIntString(key: String, ruleType: String? = "UnknownRule"): String =
    this.getStringOrIntString(key)
        ?: throw ConfigurationException.RuleParsingException("\"$key\" (string or integer) is required for $ruleType rule.")
// --- End JsonNode Helper Extensions ---


object RuleParser {
    private val logger = LoggerFactory.getLogger(RuleParser::class.java) // Added logger

    @Throws(ConfigurationException::class)
    fun parseRuleManager(configNode: JsonNode?, adapterFactoryManager: AdapterFactoryManager): RuleManager {
        if (configNode == null || configNode.isNull || configNode.isMissingNode) {
            logger.info("No rule section found or it's null/missing, creating RuleManager with default direct rule.")
            // Original Swift code's parseRuleManager in Configuration.swift would create an empty rules list and appendDirect=true
            // If rule section is missing, this seems to be the behavior.
            return RuleManager(rules = emptyList(), appendDirect = true)
        }
        if (!configNode.isArray) {
            throw ConfigurationException.RuleParsingException("Rule section must be an array.")
        }

        val rules = mutableListOf<Rule>()
        for (ruleConfigNode in configNode.elements()) { // Iterate ArrayNode
            if (!ruleConfigNode.isObject) {
                logger.warn("Skipping non-object entry in rule configuration list.")
                continue
            }
            rules.add(parseRule(ruleConfigNode, adapterFactoryManager))
        }
        // The `appendDirect` flag from Swift's RuleManager.init(fromRules:appendDirect:) seems to default to true.
        return RuleManager(rules = rules, appendDirect = true)
    }

    @Throws(ConfigurationException::class)
    private fun parseRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): Rule {
        val type = config.getOptString("type")?.lowercase()
            ?: throw ConfigurationException.RuleTypeMissingException()

        return when (type) {
            "country" -> parseCountryRule(config, adapterFactoryManager)
            "all" -> parseAllRule(config, adapterFactoryManager)
            "list", "domainlist" -> parseDomainListRule(config, adapterFactoryManager)
            "iplist" -> parseIPRangeListRule(config, adapterFactoryManager)
            "dnsfail" -> parseDNSFailRule(config, adapterFactoryManager)
            else -> throw ConfigurationException.UnknownRuleTypeException(type)
        }
    }

    @Throws(ConfigurationException::class)
    private fun parseCountryRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): CountryRule {
        val country = config.getReqString("country", ruleType = "country")
        val adapterId = config.getReqStringOrIntString("adapter", ruleType = "country")
        val adapter = adapterFactoryManager[adapterId] // Using AdapterFactoryManager's get operator
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for country rule.")
        val match = config.getReqBool("match", ruleType = "country")
        return CountryRule(country, match, adapter)
    }

    @Throws(ConfigurationException::class)
    private fun parseAllRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): AllRule {
        val adapterId = config.getReqStringOrIntString("adapter", ruleType = "all")
        val adapter = adapterFactoryManager[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for all rule.")
        return AllRule(adapter)
    }

    @Throws(ConfigurationException::class)
    private fun parseDomainListRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): DomainListRule {
        val adapterId = config.getReqStringOrIntString("adapter", ruleType = "domainlist")
        val adapter = adapterFactoryManager[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for domain list rule.")
        var filepath = config.getReqStringOrIntString("file", ruleType = "domainlist")

        filepath = expandTilde(filepath)

        try {
            val content = File(filepath).readText(Charsets.UTF_8)
            val lines = content.lines()
            val criteria = mutableListOf<DomainListRule.MatchCriterion>()
            for (line in lines) {
                if (line.isNotBlank() && !line.startsWith("#")) {
                    criteria.add(DomainListRule.MatchCriterion.RegexCriterion(Regex(line, RegexOption.IGNORE_CASE)))
                }
            }
            return DomainListRule(adapter, criteria)
        } catch (e: IOException) {
            throw ConfigurationException.RuleParsingException("Error reading domain list file '$filepath': ${e.message}")
        } catch (e: Exception) {
            throw ConfigurationException.RuleParsingException("Error parsing domain list file '$filepath': ${e.message}")
        }
    }

    @Throws(ConfigurationException::class)
    private fun parseIPRangeListRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): IPRangeListRule {
        val adapterId = config.getReqStringOrIntString("adapter", ruleType = "iplist")
        val adapter = adapterFactoryManager[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for IP range list rule.")
        var filepath = config.getReqStringOrIntString("file", ruleType = "iplist")

        filepath = expandTilde(filepath)

        try {
            val content = File(filepath).readText(Charsets.UTF_8)
            val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            return IPRangeListRule(adapter, lines)
        } catch (e: IOException) {
            throw ConfigurationException.RuleParsingException("Error reading IP range list file '$filepath': ${e.message}")
        } catch (e: Exception) {
            throw ConfigurationException.RuleParsingException("Error processing IP range list file '$filepath': ${e.message}")
        }
    }

    @Throws(ConfigurationException::class)
    private fun parseDNSFailRule(config: JsonNode, adapterFactoryManager: AdapterFactoryManager): DNSFailRule {
        val adapterId = config.getReqStringOrIntString("adapter", ruleType = "dnsfail")
        val adapter = adapterFactoryManager[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for DNS fail rule.")
        return DNSFailRule(adapter)
    }
}
