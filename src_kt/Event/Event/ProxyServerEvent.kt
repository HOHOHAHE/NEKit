// Assuming EventType.kt is in this package or imported.
// Assuming ProxySocket, ProxyServer, Tunnel are available (possibly as placeholders).

// --- Placeholders for external types (if not already defined in shared context) ---
// class ProxySocket { override fun toString(): String = "ProxySocket(${hashCode()})" }
// class ProxyServer { override fun toString(): String = "ProxyServer(${hashCode()})" }
// class Tunnel { override fun toString(): String = "Tunnel(${hashCode()})" }
// --- End Placeholders ---


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
