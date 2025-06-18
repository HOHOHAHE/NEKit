package io.github.hohohahe.nekitkotlin.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonSubTypes

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = DirectAdapterConfig::class, name = "Direct"),
    JsonSubTypes.Type(value = HttpAdapterConfig::class, name = "HTTP"),
    JsonSubTypes.Type(value = Socks5AdapterConfig::class, name = "SOCKS5")
    // Add other adapter types here as they are implemented
)
sealed class AdapterConfig {
    abstract val type: String
}

data class DirectAdapterConfig(
    override val type: String = "Direct"
) : AdapterConfig()

data class HttpAdapterConfig(
    override val type: String = "HTTP",
    val host: String,
    val port: Int,
    val auth: AuthenticationConfig? = null
    // Add other HTTP-specific properties if needed, e.g., custom headers
) : AdapterConfig()

data class Socks5AdapterConfig(
    override val type: String = "SOCKS5",
    val host: String,
    val port: Int,
    val auth: AuthenticationConfig? = null
) : AdapterConfig()

data class AuthenticationConfig(
    val username: String,
    val password: String
)
