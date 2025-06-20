import java.io.File
import java.io.IOException
// Assuming YamlNode, AdapterFactoryManager, ConfigurationException and their helpers are available
// from previous context or defined/imported.
// Also assuming placeholder Rule types.

// --- Re-iterate or assume YamlNode and helpers if not in shared context ---
// typealias YamlNode = Map<String, Any> // Or Any for more flexibility
// Helper extensions for YamlNode (Map<String, Any> or Any)
// fun YamlNode?.asString(): String? = this as? String
// fun YamlNode?.asInt(): Int? = (this as? Number)?.toInt()
// fun YamlNode?.asBoolean(): Boolean? = this as? Boolean
// @Suppress("UNCHECKED_CAST")
// fun YamlNode?.asList(): List<YamlNode>? = this as? List<Any> // More general
// fun YamlNode?.stringOrIntString(): String? { ... } // As defined before

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
// class RuleManager(val rules: List<Rule>, val appendDirect: Boolean = true)
// Let's refine based on `fromRules` and `appendDirect` usage here:
class RuleManager(val rules: List<Rule>, val appendDirect: Boolean) {
    constructor(fromRules: List<Rule>, appendDirect: Boolean) : this(fromRules, appendDirect)
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


object RuleParser {

    @Throws(ConfigurationException::class)
    fun parseRuleManager(configNode: YamlNode?, adapterFactoryManager: AdapterFactoryManager): RuleManager {
        @Suppress("UNCHECKED_CAST")
        val ruleConfigs = (configNode as? List<YamlNode>)
            ?: throw ConfigurationException.NoRuleDefinedException()

        val rules = mutableListOf<Rule>()
        for (ruleConfigNode in ruleConfigs) {
            @Suppress("UNCHECKED_CAST")
            val ruleConfigMap = ruleConfigNode as? Map<String, Any> // Each rule config is a map
                ?: throw ConfigurationException.RuleParsingException("Rule configuration entry is not a valid map.")
            rules.add(parseRule(ruleConfigMap, adapterFactoryManager))
        }
        return RuleManager(fromRules = rules, appendDirect = true)
    }

    @Throws(ConfigurationException::class)
    private fun parseRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): Rule {
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
    private fun parseCountryRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): CountryRule {
        val country = config.getOptString("country")
            ?: throw ConfigurationException.RuleParsingException("Country code (country) is required for country rule.")
        val adapterId = config.getStringOrIntString("adapter")
            ?: throw ConfigurationException.RuleParsingException("An adapter id (adapter) is required for country rule.")
        val adapter = adapterFactoryManager.factoryDict[adapterId] // factoryDict from AdapterFactoryManager placeholder
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for country rule.")
        val match = config.getOptBool("match")
            ?: throw ConfigurationException.RuleParsingException("Match boolean (match) is required for country rule.")
        return CountryRule(country, match, adapter)
    }

    @Throws(ConfigurationException::class)
    private fun parseAllRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): AllRule {
        val adapterId = config.getStringOrIntString("adapter")
            ?: throw ConfigurationException.RuleParsingException("An adapter id (adapter) is required for all rule.")
        val adapter = adapterFactoryManager.factoryDict[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for all rule.")
        return AllRule(adapter)
    }

    @Throws(ConfigurationException::class)
    private fun parseDomainListRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): DomainListRule {
        val adapterId = config.getStringOrIntString("adapter")
            ?: throw ConfigurationException.RuleParsingException("An adapter id (adapter) is required for domain list rule.")
        val adapter = adapterFactoryManager.factoryDict[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for domain list rule.")
        var filepath = config.getStringOrIntString("file")
            ?: throw ConfigurationException.RuleParsingException("File path (file) for domain list is required.")

        filepath = expandTilde(filepath)

        try {
            val content = File(filepath).readText(Charsets.UTF_8)
            val lines = content.lines()
            val criteria = mutableListOf<DomainListRule.MatchCriterion>()
            for (line in lines) {
                if (line.isNotBlank() && !line.startsWith("#")) { // Skip empty lines and comments
                    // Original Swift code used NSRegularExpression. This uses Kotlin Regex.
                    // Options like .caseInsensitive are set directly in Regex constructor.
                    criteria.add(DomainListRule.MatchCriterion.RegexCriterion(Regex(line, RegexOption.IGNORE_CASE)))
                }
            }
            return DomainListRule(adapter, criteria)
        } catch (e: IOException) {
            throw ConfigurationException.RuleParsingException("Error reading domain list file '$filepath': ${e.message}")
        } catch (e: Exception) { // Catch other errors like RegexPatternSyntaxException
            throw ConfigurationException.RuleParsingException("Error parsing domain list file '$filepath': ${e.message}")
        }
    }

    @Throws(ConfigurationException::class)
    private fun parseIPRangeListRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): IPRangeListRule {
        val adapterId = config.getStringOrIntString("adapter")
            ?: throw ConfigurationException.RuleParsingException("An adapter id (adapter) is required for IP range list rule.")
        val adapter = adapterFactoryManager.factoryDict[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for IP range list rule.")
        var filepath = config.getStringOrIntString("file")
            ?: throw ConfigurationException.RuleParsingException("File path (file) for IP range list is required.")

        filepath = expandTilde(filepath)

        try {
            val content = File(filepath).readText(Charsets.UTF_8)
            val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") } // Skip empty and comments
            // The IPRangeListRule constructor is expected to handle parsing of these strings.
            return IPRangeListRule(adapter, lines)
        } catch (e: IOException) {
            throw ConfigurationException.RuleParsingException("Error reading IP range list file '$filepath': ${e.message}")
        } catch (e: Exception) { // Catch other errors from IPRangeListRule constructor if it parses strings
            throw ConfigurationException.RuleParsingException("Error processing IP range list file '$filepath': ${e.message}")
        }
    }

    @Throws(ConfigurationException::class)
    private fun parseDNSFailRule(config: Map<String, Any>, adapterFactoryManager: AdapterFactoryManager): DNSFailRule {
        val adapterId = config.getStringOrIntString("adapter")
            ?: throw ConfigurationException.RuleParsingException("An adapter id (adapter) is required for DNS fail rule.")
        val adapter = adapterFactoryManager.factoryDict[adapterId]
            ?: throw ConfigurationException.RuleParsingException("Unknown adapter id '$adapterId' for DNS fail rule.")
        return DNSFailRule(adapter)
    }
}
