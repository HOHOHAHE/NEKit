package com.example.nekit.Event.Event
import com.example.nekit.Event.Event.EventType // Corrected import
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Tunnel.Tunnel


sealed class ProxyServerEvent : EventType {
    data class NewSocketAccepted(val socket: ProxySocket, val server: ProxyServer) : ProxyServerEvent()
    data class TunnelClosed(val tunnel: Tunnel, val server: ProxyServer) : ProxyServerEvent()
    data class Started(val server: ProxyServer) : ProxyServerEvent()
    data class Stopped(val server: ProxyServer) : ProxyServerEvent()

    override fun toString(): String {
        return when (this) {
            is NewSocketAccepted -> "Proxy server $server just accepted a new socket $socket."
            is TunnelClosed -> "A tunnel $tunnel on proxy server $server just closed."
            is Started -> "Proxy server $server started."
            is Stopped -> "Proxy server $server stopped."
        }
    }
}
