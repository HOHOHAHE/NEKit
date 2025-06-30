package com.example.nekit.Event

import com.example.nekit.Event.Observer
import com.example.nekit.Event.Event.AdapterSocketEvent
import com.example.nekit.Event.Event.ProxyServerEvent
import com.example.nekit.Event.Event.ProxySocketEvent
import com.example.nekit.Event.Event.TunnelEvent
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Tunnel.Tunnel

object ObserverFactory {

    open fun getObserverForAdapterSocket(socket: AdapterSocket): Observer<AdapterSocketEvent>? = null
    open fun getObserverForProxyServer(server: ProxyServer): Observer<ProxyServerEvent>? = null
    open fun getObserverForProxySocket(socket: ProxySocket): Observer<ProxySocketEvent>? = null
    open fun getObserverForTunnel(tunnel: Tunnel): Observer<TunnelEvent>? = null
    open fun getObserverForRuleManager(manager: com.example.nekit.Rule.RuleManager): Observer<com.example.nekit.Event.Event.RuleMatchEvent>? = null
}