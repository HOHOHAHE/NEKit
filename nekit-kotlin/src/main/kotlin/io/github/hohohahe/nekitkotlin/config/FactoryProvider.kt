package io.github.hohohahe.nekitkotlin.config

import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.HttpAdapterFactory
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.Socks5AdapterFactory

object FactoryProvider {
    fun getFactory(config: AdapterConfig): AdapterFactory {
        return when (config) {
            is DirectAdapterConfig -> DirectAdapterFactory()
            is HttpAdapterConfig -> HttpAdapterFactory(
                proxyHost = config.host,
                proxyPort = Port(config.port)
                // TODO: Add auth handling config.auth
            )
            is Socks5AdapterConfig -> Socks5AdapterFactory(
                proxyHost = config.host,
                proxyPort = Port(config.port)
                // TODO: Add auth handling config.auth
            )
            // Add other adapter types here
        }
    }
}
