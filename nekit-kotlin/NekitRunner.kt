package io.github.hohohahe.nekitkotlin

import io.github.hohohahe.nekitkotlin.config.AdapterFactoryConfig
import io.github.hohohahe.nekitkotlin.config.FullNekitConfig
import io.github.hohohahe.nekitkotlin.config.RuleConfig
import io.github.hohohahe.nekitkotlin.proxyserver.NettyProxyServer
import io.github.hohohahe.nekitkotlin.proxyserver.ProxyServer
import io.github.hohohahe.nekitkotlin.rule.*
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.HttpAdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.Socks5AdapterFactory
        import kotlinx.coroutines.* // Import cancel
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class NekitRunner(private val config: FullNekitConfig) {

    private val runningServers = mutableListOf<ProxyServer>()
    private val runnerScope = CoroutineScope(Dispatchers.Default + SupervisorJob()) // For launching servers

    fun startServers() {
        if (runningServers.isNotEmpty()) {
            logger.warn { "NekitRunner: Servers are already started or starting." }
            return
        }

        logger.info { "NekitRunner: Initializing from configuration..." }

        // 1. Instantiate AdapterFactories
        val adapterFactories = mutableMapOf<String, AdapterFactory>()
        config.adapterFactories.forEach { factoryConfig ->
            val factory = when (factoryConfig) {
                is AdapterFactoryConfig.DirectAdapterFactoryConfig -> DirectAdapterFactory()
                is AdapterFactoryConfig.HttpAdapterFactoryConfig -> HttpAdapterFactory(factoryConfig.host, factoryConfig.port.let { io.github.hohohahe.nekitkotlin.core.Port(it) })
                is AdapterFactoryConfig.Socks5AdapterFactoryConfig -> Socks5AdapterFactory(factoryConfig.host, factoryConfig.port.let { io.github.hohohahe.nekitkotlin.core.Port(it) })
                // Add other adapter factory types here
            }
            if (adapterFactories.containsKey(factoryConfig.name)) {
                logger.warn { "Duplicate adapter factory name '${factoryConfig.name}' found in configuration. Overwriting." }
            }
            adapterFactories[factoryConfig.name] = factory
            logger.info { "Created adapter factory: ${factoryConfig.name} (type: ${factoryConfig::class.simpleName})" }
        }
        
        // Ensure a 'direct' factory exists if not explicitly defined by user but referenced by rules.
        if (!adapterFactories.containsKey("direct")) {
            logger.info{"No 'direct' adapter factory explicitly defined, adding a default DirectAdapterFactory."}
            adapterFactories["direct"] = DirectAdapterFactory()
        }


        // 2. Instantiate Rules
        val rules = mutableListOf<Rule>()
        config.rules.forEach { ruleConfig ->
            val selectedAdapterFactory = adapterFactories[ruleConfig.adapter]
            if (selectedAdapterFactory == null) {
                logger.error { "Adapter factory named '${ruleConfig.adapter}' not found for rule type '${ruleConfig::class.simpleName}'. Skipping this rule." }
                return@forEach // continue to next ruleConfig
            }

            val rule = when (ruleConfig) {
                is RuleConfig.DirectRuleConfig -> { // This implies DirectRule should take an AdapterFactory
                     // Using AllRule for "direct" type in config makes more sense if adapter is configurable.
                     // For now, let's assume DirectRuleConfig implies AllRule with the specified adapter.
                     logger.warn("Rule type 'direct' might be better handled by 'all' type with a direct adapter. Consider updating config. Using AllRule for now.")
                     AllRule(selectedAdapterFactory)
                }
                is RuleConfig.DomainRuleConfig -> DomainRule(ruleConfig.domains, ruleConfig.matcher, selectedAdapterFactory)
                is RuleConfig.CountryRuleConfig -> {
                    logger.warn { "CountryRuleConfig type found, but CountryRule implementation is not yet available. Skipping." }
                    null // Placeholder for future CountryRule
                }
                is RuleConfig.AllRuleConfig -> AllRule(selectedAdapterFactory)
            }
            rule?.let { rules.add(it) }
        }
        logger.info { "Initialized ${rules.size} rules." }

        // 3. Create RuleManager
        // The defaultAdapterFactory in RuleManager can be the one named "direct" or a fresh DirectAdapterFactory if "direct" is missing
        val defaultRuleManagerAdapter = adapterFactories["direct"] ?: DirectAdapterFactory().also {
            logger.info{"Using a fresh DirectAdapterFactory as default for RuleManager as 'direct' was not found."}
        }
        val ruleManager = RuleManager(rules, defaultAdapterFactory = defaultRuleManagerAdapter)
        logger.info { "RuleManager created." }

        // 4. Start Proxy Servers
        if (config.servers.isEmpty()) {
            logger.warn("No servers defined in the configuration.")
        }
        config.servers.forEach { serverConfig ->
            logger.info { "Preparing to start ${serverConfig.type} server on port ${serverConfig.port} (bind: ${serverConfig.host ?: "0.0.0.0"})" }
            val server = NettyProxyServer(
                port = serverConfig.getPort(),
                proxyType = serverConfig.type,
                ruleManager = ruleManager
                // TODO: Pass boss/worker groups if we want to share them across servers
            )
            runnerScope.launch {
                try {
                    server.start()
                    runningServers.add(server)
                    logger.info { "${serverConfig.type} server started successfully on port ${serverConfig.port}." }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to start ${serverConfig.type} server on port ${serverConfig.port}." }
                }
            }
        }
    }

    fun stopServers() {
        logger.info { "NekitRunner: Stopping all servers..." }
        runningServers.forEach { 
            try {
                it.stop() 
            } catch (e: Exception) {
                logger.warn(e) {"Error stopping server on port ${it.port.value}"}
            }
        }
        runningServers.clear()
        runnerScope.cancel("NekitRunner stopping all servers.") // Cancel all coroutines launched by this runner
        logger.info { "NekitRunner: All servers stopped." }
    }

    // Optional: A main method to run from command line with a config file path
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            if (args.isEmpty()) {
                println("Usage: java -jar nekit-kotlin.jar <path_to_config_json>")
                return
            }
            val configFile = java.io.File(args[0])
            if (!configFile.exists()) {
                println("Error: Configuration file not found at ${configFile.absolutePath}")
                return
            }
            try {
                val config = io.github.hohohahe.nekitkotlin.config.ConfigLoader.loadFromFile(configFile)
                val runner = NekitRunner(config)
                runner.startServers()
                logger.info("Nekit-Kotlin started. Press Ctrl+C to stop.")
                // Keep main thread alive until shutdown hook or explicit stop
                Runtime.getRuntime().addShutdownHook(Thread {
                    logger.info("Shutdown hook triggered. Stopping servers...")
                    runner.stopServers()
                    logger.info("Nekit-Kotlin shut down complete.")
                })
                // Keep alive for non-daemon threads from Netty, or explicitly wait.
                // For a simple CLI, this might be enough if Netty threads are non-daemon.
                // Or use something like: `while(true) { Thread.sleep(Long.MAX_VALUE) }`
                // if servers are daemonized.
                // For now, the shutdown hook is the primary stop mechanism.
            } catch (e: Exception) {
                logger.error(e) { "Error during NekitRunner initialization or server startup." }
                println("Error starting Nekit-Kotlin: ${e.message}")
            }
        }
    }
}
