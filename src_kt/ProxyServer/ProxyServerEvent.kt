package com.example.nekit.ProxyServer

import com.example.nekit.Socket.ProxySocket.ProxySocketInterface
import com.example.nekit.Tunnel.Tunnel // Added missing import

/**
 * Represents events emitted by a ProxyServer.
 */
sealed class ProxyServerEvent {
    /**
     * Event fired when the ProxyServer starts.
     * @param server The ProxyServer instance that started.
     */
    data class Started(val server: ProxyServer) : ProxyServerEvent()

    /**
     * Event fired when the ProxyServer stops.
     * @param server The ProxyServer instance that stopped.
     */
    data class Stopped(val server: ProxyServer) : ProxyServerEvent()

    /**
     * Event fired when a new client socket is accepted by the ProxyServer.
     * @param socket The accepted ProxySocketInterface.
     * @param server The ProxyServer instance that accepted the socket.
     */
    data class NewSocketAccepted(val socket: ProxySocketInterface, val server: ProxyServer) : ProxyServerEvent()

    /**
     * Event fired when a Tunnel managed by the ProxyServer closes.
     * @param tunnel The Tunnel instance that closed.
     * @param server The ProxyServer instance that managed the tunnel.
     */
    data class TunnelClosed(val tunnel: Tunnel, val server: ProxyServer) : ProxyServerEvent()
}
