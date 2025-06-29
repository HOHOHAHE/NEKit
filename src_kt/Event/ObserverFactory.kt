package Event
import Event.Observer // Corrected import
import Event.EventType // Corrected import
import Tunnel.Tunnel // Corrected import
import Socket.AdapterSocket.AdapterSocket // Corrected import
import Socket.ProxySocket.ProxySocket // Corrected import
import ProxyServer.ProxyServer // Corrected import
import Rule.RuleManager // Corrected import
import Event.Event.TunnelEvent // Corrected import
import Event.Event.AdapterSocketEvent // Corrected import
import Event.Event.ProxySocketEvent // Corrected import
import Event.Event.ProxyServerEvent // Corrected import
import Event.Event.RuleMatchEvent // Corrected import


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
