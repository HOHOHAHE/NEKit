import Event.EventType // Corrected import
import Socket.ProxySocket.ProxySocket // Corrected import
import ProxyServer.ProxyServer // Corrected import
import Tunnel.Tunnel // Corrected import


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
