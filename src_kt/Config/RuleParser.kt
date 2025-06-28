package Config

import java.io.File
import java.io.IOException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import Config.ConfigurationException
import Config.ConfigurationException.RuleParsingException
import Socket.AdapterSocket.Factory.AdapterFactoryManager // Corrected import
import Socket.AdapterSocket.Factory.AdapterFactory // Corrected import
import Config.getOptString
import Config.getOptInt
import Config.getOptBool
import Config.getReqString
import Config.getStringOrIntString
import Config.getReqStringOrIntString
import Config.getReqBool

import Rule.Rule
import Rule.RuleManager
import Rule.CountryRule
import Rule.AllRule
import Rule.DomainListRule
import Rule.IPRangeListRule
import Rule.DNSFailRule

// Removed placeholder Rule types and RuleManager, as they are now imported from Rule package.

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

// --- JsonNode Helper Extensions (Moved to ConfigExtensions.kt)---
// The actual implementations are in ConfigExtensions.kt.
// These are commented out as they are now imported implicitly or explicitly.
// --- End JsonNode Helper Extensions ---


object RuleParser {
    private val logger = LoggerFactory.getLogger(RuleParser::class.java)

    @Throws(ConfigurationException::class)
    fun parseRuleManager(configNode: JsonNode?, adapterFactoryManager: AdapterFactoryManager): RuleManager {
        if (configNode == null || configNode.isNull || configNode.isMissingNode) {
            logger.info("No rule section found or it's null/missing, creating RuleManager with default direct rule.")
            // Original Swift code's parseRuleManager in Configuration.swift would create an empty rules list and appendDirect=true
            // If rule section is missing, this seems to be the behavior.
            return RuleManager(emptyList(), true)
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
        return RuleManager(rules, true)
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
