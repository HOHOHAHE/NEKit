package nekit.Event

import nekit.Event.Observer
import nekit.Event.Event.AdapterSocketEvent
import nekit.Event.Event.ProxyServerEvent
import nekit.Event.Event.ProxySocketEvent
import nekit.Event.Event.TunnelEvent
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.ProxyServer.ProxyServer
import nekit.Socket.ProxySocket.ProxySocket
import nekit.Tunnel.Tunnel

object ObserverFactory {

    open fun getObserverForAdapterSocket(socket: AdapterSocket): Observer<AdapterSocketEvent>? = null
    open fun getObserverForProxyServer(server: ProxyServer): Observer<ProxyServerEvent>? = null
    open fun getObserverForProxySocket(socket: ProxySocket): Observer<ProxySocketEvent>? = null
    open fun getObserverForTunnel(tunnel: Tunnel): Observer<TunnelEvent>? = null
    open fun getObserverForRuleManager(manager: nekit.Rule.RuleManager): Observer<nekit.Event.Event.RuleMatchEvent>? = null
}