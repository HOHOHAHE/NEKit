package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.config.AdapterConfig
import io.github.hohohahe.nekitkotlin.config.DirectRuleConfig
import io.github.hohohahe.nekitkotlin.config.DomainListRuleConfig
import io.github.hohohahe.nekitkotlin.config.FactoryProvider
import io.github.hohohahe.nekitkotlin.config.RuleConfig
import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class RuleManager(
    ruleConfigs: List<RuleConfig>,
    defaultAdapterConfig: AdapterConfig
) {
    private val rules: List<Rule>
    private val defaultAdapterFactory: AdapterFactory

    init {
        this.defaultAdapterFactory = FactoryProvider.getFactory(defaultAdapterConfig)
        this.rules = ruleConfigs.map { convertRuleConfigToRule(it) }
    }

    private fun convertRuleConfigToRule(ruleConfig: RuleConfig): Rule {
        return when (ruleConfig) {
            is DirectRuleConfig -> DirectRule(FactoryProvider.getFactory(ruleConfig.adapter))
            is DomainListRuleConfig -> {
                logger.warn { "DomainListRuleConfig encountered, but DomainListRule not fully implemented. Using placeholder rule logic." }
                // This is a placeholder. A real DomainListRule class should be implemented.
                object : Rule {
                    private val factory = FactoryProvider.getFactory(ruleConfig.adapter)
                    override fun match(session: ConnectSession): AdapterFactory? {
                        // Basic placeholder matching logic for DomainListRule
                        if (ruleConfig.domains.any { domain -> session.host.endsWith(domain.removePrefix(".")) || session.host == domain }) {
                            return factory
                        }
                        return null
                    }
                }
            }
            // else -> throw NotImplementedError("Rule type \${ruleConfig.type} not implemented yet.")
        }
    }

    fun match(session: ConnectSession): AdapterFactory {
        for (rule in rules) {
            val factory = rule.match(session)
            if (factory != null) {
                logger.debug { "Session for \${session.host}:\${session.port} matched by rule: \${rule::class.simpleName}. Using factory: \${factory::class.simpleName}" }
                return factory
            }
        }
        logger.debug { "No specific rule matched for \${session.host}:\${session.port}. Using default factory: \${defaultAdapterFactory::class.simpleName}" }
        return defaultAdapterFactory
    }
}
