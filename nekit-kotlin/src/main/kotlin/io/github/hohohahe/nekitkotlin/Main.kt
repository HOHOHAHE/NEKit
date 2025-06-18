package io.github.hohohahe.nekitkotlin

import io.github.hohohahe.nekitkotlin.config.ConfigLoader
import io.github.hohohahe.nekitkotlin.config.Configuration
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.proxyserver.NettyProxyServer
import io.github.hohohahe.nekitkotlin.proxyserver.ProxyServer
import io.github.hohohahe.nekitkotlin.proxyserver.ProxyType
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

object NekitKotlinApp {
    private val runningServers = mutableListOf<ProxyServer>()
    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start(config: Configuration) {
        logger.info { "Starting NekitKotlinApp with configuration..." }

        val ruleManager = RuleManager(config.rules, config.defaultAdapter)

        config.servers.forEach { serverConfig ->
            val proxyType = when (serverConfig.type.uppercase()) {
                "HTTP" -> ProxyType.HTTP
                "SOCKS5" -> ProxyType.SOCKS5
                else -> {
                    logger.warn { "Unsupported server type: \${serverConfig.type}. Skipping." }
                    return@forEach
                }
            }

            val server = NettyProxyServer(
                port = Port(serverConfig.port),
                proxyType = proxyType,
                ruleManager = ruleManager
            )
            runningServers.add(server)
            appScope.launch {
                try {
                    server.start()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to start server on port \${serverConfig.port}" }
                }
            }
        }
        logger.info { "NekitKotlinApp started with \${runningServers.size} server(s)." }
    }

    fun stop() {
        logger.info { "Stopping NekitKotlinApp..." }
        runningServers.forEach { server ->
            try {
                server.stop()
            } catch (e: Exception) {
                logger.warn(e) { "Error stopping server on port \${server.port.value}" }
            }
        }
        runningServers.clear()
        appScope.coroutineContext.cancelChildren()
        logger.info { "NekitKotlinApp stopped." }
    }
}

fun main(args: Array<String>) = runBlocking {
    val configPath = args.firstOrNull() ?: "config.yaml"
    logger.info { "Attempting to load configuration from: \$configPath" }

    val configFile = File(configPath)
    if (!configFile.exists()) {
        logger.warn { "Configuration file '\$configPath' not found. Creating a sample one." }
        configFile.writeText(createSampleYamlConfig())
        logger.info { "Sample 'config.yaml' created. Please review and restart." }
        return@runBlocking
    }

    try {
        val configuration = ConfigLoader.loadFromFile(configPath)
        NekitKotlinApp.start(configuration)

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutdown hook triggered. Stopping application..." }
            NekitKotlinApp.stop()
            logger.info { "Application stopped." }
        })

        while (true) {
            delay(60000)
        }

    } catch (e: Exception) {
        logger.error(e) { "Error during application startup or runtime: \${e.message}" }
    }
}

fun createSampleYamlConfig(): String {
    return """
    # Nekit-Kotlin Configuration File
    # This file configures the proxy servers, routing rules, and default behaviors.

    # 'servers': Defines a list of proxy servers to run.
    # Each server has a 'port' and 'type' (either "SOCKS5" or "HTTP").
    servers:
      - port: 10801
        type: "SOCKS5" # Listens for SOCKS5 proxy requests on port 10801
      - port: 10802
        type: "HTTP"   # Listens for HTTP proxy requests on port 10802

    # 'rules': Defines a list of routing rules. Rules are evaluated in order.
    # The first rule that matches a connection will determine how it's handled.
    rules:
      # Example 1: DomainList Rule
      # This rule matches connections based on the requested domain name.
      - type: "DomainList"
        # 'domains': A list of domain names or patterns.
        #   - "example.com": Matches exactly "example.com".
        #   - ".example.net": Matches "example.net" and any subdomains (e.g., "www.example.net").
        domains:
          - "example.com"
          - ".example.net"
        # 'matcher': (Optional, default: "Suffix" if not specified) How to match domains.
        #   Currently, the placeholder implementation in RuleManager uses basic string matching.
        #   Planned matchers: "Suffix", "Keyword", "Regex".
        matcher: "Suffix"
        # 'adapter': Specifies which adapter to use if this rule matches.
        adapter:
          type: "Direct" # Use a direct connection for these domains.

      # Example 2: Using an HTTP proxy for specific domains (currently commented out)
      # - type: "DomainList"
      #   domains:
      #     - "api.example.org"
      #   matcher: "Suffix"
      #   adapter:
      #     type: "HTTP"
      #     host: "your.upstream.http.proxy.com"
      #     port: 8080
      #     # 'auth': (Optional) Authentication for the upstream HTTP proxy.
      #     # auth:
      #     #   username: "proxy_user"
      #     #   password: "proxy_password"

      # Example 3: Direct Rule
      # This rule, if matched (e.g., by being the last rule or specific criteria not yet implemented),
      # will use the specified adapter. 'DirectRule' itself doesn't have matching criteria beyond its existence.
      # To make it a catch-all, it should typically be the last rule in the list if other rules are more specific.
      - type: "Direct" # Rule type. 'DirectRule' uses the adapter defined below.
        adapter:
          type: "Direct" # Use a direct connection.

    # 'defaultAdapter': Defines the adapter to use if no rules match a connection.
    defaultAdapter:
      type: "Direct" # All traffic not matching any rule will use a direct connection.
      # Example for a default SOCKS5 proxy (if you want unnatched traffic to go via SOCKS5):
      # type: "SOCKS5"
      # host: "your.default.socks5.proxy.com"
      # port: 1080
      # # 'auth': (Optional) Authentication for the upstream SOCKS5 proxy.
      # # auth:
      # #   username: "socks_user"
      # #   password: "socks_password"
    """.trimIndent()
}
