package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

// Mock rule for testing purposes
class MockRule(private val matches: Boolean, private val factoryToReturn: AdapterFactory?) : Rule {
    override fun match(session: ConnectSession): AdapterFactory? {
        return if (matches) factoryToReturn else null
    }
}

class RuleManagerTest {

    @Test
    fun `RuleManager returns factory from the first matching rule`() {
        val session = ConnectSession("example.com", Port(80))
        val expectedFactory = DirectAdapterFactory() // Example factory
        val rule1 = MockRule(matches = false, null)
        val rule2 = MockRule(matches = true, expectedFactory)
        val rule3 = MockRule(matches = true, DirectAdapterFactory()) // Should not be reached

        val ruleManager = RuleManager(listOf(rule1, rule2, rule3))
        val resultFactory = ruleManager.match(session)

        assertSame(expectedFactory, resultFactory, "Should return factory from the first matching rule (rule2)")
    }

    @Test
    fun `RuleManager returns default factory if no rules match`() {
        val session = ConnectSession("example.com", Port(80))
        val rule1 = MockRule(matches = false, null)
        val rule2 = MockRule(matches = false, null)

        val defaultFactory = DirectAdapterFactory()
        val ruleManager = RuleManager(listOf(rule1, rule2), defaultAdapterFactory = defaultFactory)
        val resultFactory = ruleManager.match(session)

        assertSame(defaultFactory, resultFactory, "Should return default factory if no rules match")
    }

    @Test
    fun `RuleManager uses built-in DirectAdapterFactory as default if no default provided`() {
        val session = ConnectSession("example.com", Port(80))
        val rule1 = MockRule(matches = false, null)

        val ruleManager = RuleManager(listOf(rule1)) // No explicit default factory
        val resultFactory = ruleManager.match(session)

        assertInstanceOf(DirectAdapterFactory::class.java, resultFactory, "Should use internal DirectAdapterFactory if no default is provided and no rules match")
    }
}
