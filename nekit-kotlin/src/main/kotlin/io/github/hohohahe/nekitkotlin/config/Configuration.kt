package io.github.hohohahe.nekitkotlin.config

data class ServerConfig(
    val port: Int,
    val type: String // "HTTP" or "SOCKS5"
)

data class Configuration(
    val servers: List<ServerConfig>,
    val rules: List<RuleConfig>,
    val defaultAdapter: AdapterConfig = DirectAdapterConfig() // Default to Direct if not specified
    // Add other global settings like logging level, DNS settings etc.
)
