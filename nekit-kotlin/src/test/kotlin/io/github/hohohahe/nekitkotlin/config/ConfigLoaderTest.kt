package io.github.hohohahe.nekitkotlin.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.FileNotFoundException

class ConfigLoaderTest {

    @Test
    fun `load valid sample configuration from resources`() {
        val resourceUrl = this::class.java.classLoader.getResource("sample-test-config.yaml")
        assertNotNull(resourceUrl, "Test configuration file sample-test-config.yaml not found in resources.")

        // Using getResourceAsStream is generally safer for resources
        val inputStream = this::class.java.classLoader.getResourceAsStream("sample-test-config.yaml")
        assertNotNull(inputStream, "Failed to get InputStream for sample-test-config.yaml")

        val configuration = ConfigLoader.loadFromInputStream(inputStream!!)

        // Test server configurations
        assertEquals(2, configuration.servers.size)
        configuration.servers[0].let { server ->
            assertEquals(19080, server.port)
            assertEquals("SOCKS5", server.type)
        }
        configuration.servers[1].let { server ->
            assertEquals(19081, server.port)
            assertEquals("HTTP", server.type)
        }

        // Test rule configurations
        assertEquals(2, configuration.rules.size)

        // First rule: DomainList with DirectAdapter
        val rule1 = configuration.rules[0] as? DomainListRuleConfig
        assertNotNull(rule1, "First rule should be DomainListRuleConfig")
        assertEquals("DomainList", rule1!!.type)
        assertEquals(listOf("test.com", ".nekitempty.org"), rule1.domains)
        assertEquals("Suffix", rule1.matcher)
        assertInstanceOf(DirectAdapterConfig::class.java, rule1.adapter, "Adapter for first rule should be DirectAdapterConfig")

        // Second rule: DirectRule with HttpAdapter
        val rule2 = configuration.rules[1] as? DirectRuleConfig
        assertNotNull(rule2, "Second rule should be DirectRuleConfig")
        assertEquals("Direct", rule2!!.type)
        val httpAdapterForRule2 = rule2.adapter as? HttpAdapterConfig
        assertNotNull(httpAdapterForRule2, "Adapter for second rule should be HttpAdapterConfig")
        assertEquals("HTTP", httpAdapterForRule2!!.type)
        assertEquals("proxy.example.com", httpAdapterForRule2.host)
        assertEquals(8000, httpAdapterForRule2.port)
        assertNotNull(httpAdapterForRule2.auth, "Auth config for HTTP adapter should not be null")
        assertEquals("testuser", httpAdapterForRule2.auth!!.username)
        assertEquals("testpassword", httpAdapterForRule2.auth!!.password)


        // Test default adapter configuration
        val defaultAdapter = configuration.defaultAdapter as? Socks5AdapterConfig
        assertNotNull(defaultAdapter, "Default adapter should be Socks5AdapterConfig")
        assertEquals("SOCKS5", defaultAdapter!!.type)
        assertEquals("default.socks.proxy", defaultAdapter.host)
        assertEquals(1080, defaultAdapter.port)
    }

    @Test
    fun `load non-existent configuration file throws exception`() {
        assertThrows<ConfigException>("Should throw ConfigException for non-existent file") {
            ConfigLoader.loadFromFile("non-existent-config.yaml")
        }
    }

    @Test
    fun `load configuration from InputStream`() {
        val yamlContent = """
        servers:
          - port: 9999
            type: "HTTP"
        rules: []
        defaultAdapter:
          type: "Direct"
        """.trimIndent()

        val inputStream = yamlContent.byteInputStream()
        val configuration = ConfigLoader.loadFromInputStream(inputStream)

        assertEquals(1, configuration.servers.size)
        assertEquals(9999, configuration.servers[0].port)
        assertTrue(configuration.rules.isEmpty())
        assertInstanceOf(DirectAdapterConfig::class.java, configuration.defaultAdapter)
    }
}
