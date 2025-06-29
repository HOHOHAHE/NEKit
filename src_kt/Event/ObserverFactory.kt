package com.example.nekit.Event
import com.example.nekit.Event.Observer // Corrected import
import com.example.nekit.Event.EventType // Corrected import
import com.example.nekit.Tunnel.Tunnel
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Rule.RuleManager
import com.example.nekit.Event.Event.TunnelEvent // Corrected import
import com.example.nekit.Event.Event.AdapterSocketEvent // Corrected import
import com.example.nekit.Event.Event.ProxySocketEvent // Corrected import
import com.example.nekit.Event.Event.ProxyServerEvent // Corrected import
import com.example.nekit.Event.Event.RuleMatchEvent // Corrected import


/**
 * Base factory class for creating observers.
 * Subclasses should override the `getObserverFor...` methods to provide specific observer instances.
 */
open class ObserverFactory {

    /**
     * Default constructor.
     */
    constructor()

    companion object {
        /**
         * Stores the currently active factory instance.
         * This allows for a globally accessible factory.
         */
        @JvmStatic
        var currentFactory: ObserverFactory? = null
    }

    /**
     * Returns an observer for a Tunnel.
     * Base implementation returns null.
     *
     * @param tunnel The tunnel instance.
     * @return An [Observer] for [TunnelEvent]s, or null.
     */
    open fun getObserverForTunnel(tunnel: Tunnel): Observer<TunnelEvent>? {
        return null
    }

    /**
     * Returns an observer for an AdapterSocket.
     * Base implementation returns null.
     *
     * @param socket The adapter socket instance.
     * @return An [Observer] for [AdapterSocketEvent]s, or null.
     */
    open fun getObserverForAdapterSocket(socket: AdapterSocket): Observer<AdapterSocketEvent>? {
        return null
    }

    /**
     * Returns an observer for a ProxySocket.
     * Base implementation returns null.
     *
     * @param socket The proxy socket instance.
     * @return An [Observer] for [ProxySocketEvent]s, or null.
     */
    open fun getObserverForProxySocket(socket: ProxySocket): Observer<ProxySocketEvent>? {
        return null
    }

    /**
     * Returns an observer for a ProxyServer.
     * Base implementation returns null.
     *
     * @param server The proxy server instance.
     * @return An [Observer] for [ProxyServerEvent]s, or null.
     */
    open fun getObserverForProxyServer(server: ProxyServer): Observer<ProxyServerEvent>? {
        return null
    }

    /**
     * Returns an observer for a RuleManager.
     * Base implementation returns null.
     *
     * @param manager The rule manager instance.
     * @return An [Observer] for [RuleMatchEvent]s, or null.
     */
    open fun getObserverForRuleManager(manager: RuleManager): Observer<RuleMatchEvent>? {
        return null
    }
}
