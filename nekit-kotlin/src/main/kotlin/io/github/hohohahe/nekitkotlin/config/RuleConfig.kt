package io.github.hohohahe.nekitkotlin.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonSubTypes

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = DirectRuleConfig::class, name = "Direct"),
    JsonSubTypes.Type(value = DomainListRuleConfig::class, name = "DomainList")
    // Add other rule types here
)
sealed class RuleConfig {
    abstract val type: String
    abstract val adapter: AdapterConfig // Defines which adapter this rule will use
}

data class DirectRuleConfig(
    override val type: String = "Direct",
    override val adapter: AdapterConfig
) : RuleConfig()

data class DomainListRuleConfig(
    override val type: String = "DomainList",
    val domains: List<String>, // List of domains for this rule
    val matcher: String = "Suffix", // e.g., Suffix, Keyword, Regex
    override val adapter: AdapterConfig
) : RuleConfig()

// Example for other rule types (can be added later)
// data class IPRangeListRuleConfig(
//     override val type: String = "IPRangeList",
//     val ipRanges: List<String>, // e.g., ["192.168.1.0/24"]
//     override val adapter: AdapterConfig
// ) : RuleConfig()

// data class AllRuleConfig(
//     override val type: String = "All",
//     override val adapter: AdapterConfig
// ) : RuleConfig()
