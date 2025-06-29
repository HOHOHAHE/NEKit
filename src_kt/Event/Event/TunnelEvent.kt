package Event.Event
import Event.EventType // Corrected import
import Tunnel.Tunnel // Corrected import
import Messages.ConnectSession // Corrected import
import Socket.ProxySocket.ProxySocket // Corrected import
import Socket.AdapterSocket.AdapterSocket // Corrected import
import Socket.SocketProtocol // Corrected import

sealed class TunnelEvent : EventType {
    data class Opened(val tunnel: Tunnel) : TunnelEvent()
    data class CloseCalled(val tunnel: Tunnel) : TunnelEvent()
    data class ForceCloseCalled(val tunnel: Tunnel) : TunnelEvent()
    data class ReceivedRequest(val request: ConnectSession, val fromSocket: ProxySocket, val onTunnel: Tunnel) : TunnelEvent()
    data class ReceivedReadySignal(val socket: SocketProtocol, val currentReadySignal: Int, val onTunnel: Tunnel) : TunnelEvent()
    data class ProxySocketReadData(val data: ByteArray, val fromSocket: ProxySocket, val onTunnel: Tunnel) : TunnelEvent() {
        override fun equals(other: Any?): Boolean { // For ByteArray content
            if (this === other) return true; if (javaClass != other?.javaClass) return false; other as ProxySocketReadData
            return data.contentEquals(other.data) && fromSocket == other.fromSocket && onTunnel == other.onTunnel
        }
        override fun hashCode(): Int = (31 * (31 * data.contentHashCode() + fromSocket.hashCode())) + onTunnel.hashCode()
    }
    data class ProxySocketWroteData(val data: ByteArray?, val bySocket: ProxySocket, val onTunnel: Tunnel) : TunnelEvent() {
         override fun equals(other: Any?): Boolean { // For ByteArray content
            if (this === other) return true; if (javaClass != other?.javaClass) return false; other as ProxySocketWroteData
            val dataEquals = if (data != null) other.data != null && data.contentEquals(other.data) else other.data == null
            return dataEquals && bySocket == other.bySocket && onTunnel == other.onTunnel
        }
        override fun hashCode(): Int = (31 * (31 * (data?.contentHashCode() ?: 0) + bySocket.hashCode())) + onTunnel.hashCode()
    }
    data class AdapterSocketReadData(val data: ByteArray, val fromSocket: AdapterSocket, val onTunnel: Tunnel) : TunnelEvent() {
         override fun equals(other: Any?): Boolean { // For ByteArray content
            if (this === other) return true; if (javaClass != other?.javaClass) return false; other as AdapterSocketReadData
            return data.contentEquals(other.data) && fromSocket == other.fromSocket && onTunnel == other.onTunnel
        }
        override fun hashCode(): Int = (31 * (31 * data.contentHashCode() + fromSocket.hashCode())) + onTunnel.hashCode()
    }
    data class AdapterSocketWroteData(val data: ByteArray?, val bySocket: AdapterSocket, val onTunnel: Tunnel) : TunnelEvent() {
        override fun equals(other: Any?): Boolean { // For ByteArray content
            if (this === other) return true; if (javaClass != other?.javaClass) return false; other as AdapterSocketWroteData
            val dataEquals = if (data != null) other.data != null && data.contentEquals(other.data) else other.data == null
            return dataEquals && bySocket == other.bySocket && onTunnel == other.onTunnel
        }
        override fun hashCode(): Int = (31 * (31 * (data?.contentHashCode() ?: 0) + bySocket.hashCode())) + onTunnel.hashCode()
    }
    data class ConnectedToRemote(val socket: AdapterSocket, val onTunnel: Tunnel) : TunnelEvent()
    data class UpdatingAdapterSocket(val fromOldSocket: AdapterSocket, val toNewSocket: AdapterSocket, val onTunnel: Tunnel) : TunnelEvent()
    data class Closed(val tunnel: Tunnel) : TunnelEvent()

    override fun toString(): String {
        return when (this) {
            is Opened -> "Tunnel $tunnel starts processing data."
            is CloseCalled -> "Close is called on tunnel $tunnel."
            is ForceCloseCalled -> "Force close is called on tunnel $tunnel."
            is ReceivedRequest -> "Tunnel $onTunnel received request $request from proxy socket $fromSocket."
            is ReceivedReadySignal ->
                if (currentReadySignal == 1) "Tunnel $onTunnel received ready-for-forward signal from socket $socket."
                else "Tunnel $onTunnel received ready-for-forward signal from socket $socket. Start forwarding data."
            is ProxySocketReadData -> "Tunnel $onTunnel received ${data.size} bytes from proxy socket $fromSocket."
            is ProxySocketWroteData ->
                if (data != null) "Proxy socket $bySocket sent ${data.size} bytes data from Tunnel $onTunnel."
                else "Proxy socket $bySocket sent data from Tunnel $onTunnel."
            is AdapterSocketReadData -> "Tunnel $onTunnel received ${data.size} bytes from adapter socket $fromSocket."
            is AdapterSocketWroteData ->
                if (data != null) "Adapter socket $bySocket sent ${data.size} bytes data from Tunnel $onTunnel."
                else "Adapter socket $bySocket sent data from Tunnel $onTunnel."
            is ConnectedToRemote -> "Adapter socket $socket connected to remote successfully on tunnel $onTunnel."
            is UpdatingAdapterSocket -> "Updating adapter socket of tunnel $onTunnel from $fromOldSocket to $toNewSocket."
            is Closed -> "Tunnel $tunnel closed."
        }
    }
}
