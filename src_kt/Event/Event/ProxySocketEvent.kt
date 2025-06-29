package com.example.nekit.Event.Event
import com.example.nekit.Event.EventType // Corrected import
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket

sealed class ProxySocketEvent : EventType {
    data class SocketOpened(val socket: ProxySocket) : ProxySocketEvent()
    data class DisconnectCalled(val socket: ProxySocket) : ProxySocketEvent()
    data class ForceDisconnectCalled(val socket: ProxySocket) : ProxySocketEvent()
    data class Disconnected(val socket: ProxySocket) : ProxySocketEvent()
    data class ReceivedRequest(val session: ConnectSession, val socket: ProxySocket) : ProxySocketEvent()
    data class ReadData(val data: ByteArray, val socket: ProxySocket) : ProxySocketEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReadData
            if (!data.contentEquals(other.data)) return false
            if (socket != other.socket) return false
            return true
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + socket.hashCode()
            return result
        }
    }
    data class WroteData(val data: ByteArray?, val socket: ProxySocket) : ProxySocketEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WroteData
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            if (socket != other.socket) return false
            return true
        }
        override fun hashCode(): Int {
            var result = data?.contentHashCode() ?: 0
            result = 31 * result + socket.hashCode()
            return result
        }
    }
    data class AskedToResponseTo(val adapter: AdapterSocket, val socket: ProxySocket) : ProxySocketEvent()
    data class ReadyForForward(val socket: ProxySocket) : ProxySocketEvent()
    data class ErrorOccurred(val error: Throwable, val socket: ProxySocket) : ProxySocketEvent() // Swift Error -> Kotlin Throwable

    override fun toString(): String {
        return when (this) {
            is SocketOpened -> "Start processing data from proxy socket $socket."
            is DisconnectCalled -> "Disconnect is just called on proxy socket $socket."
            is ForceDisconnectCalled -> "Force disconnect is just called on proxy socket $socket."
            is Disconnected -> "Proxy socket $socket disconnected."
            is ReceivedRequest -> "Proxy socket $socket received request $session."
            is ReadData -> "Received ${data.size} bytes data on proxy socket $socket."
            is WroteData -> if (data != null) "Sent ${data.size} bytes data on proxy socket $socket." else "Sent data on proxy socket $socket."
            is AskedToResponseTo -> "Proxy socket $socket is asked to respond to adapter $adapter."
            is ReadyForForward -> "Proxy socket $socket is ready to forward data."
            is ErrorOccurred -> "Proxy socket $socket encountered an error $error."
        }
    }
}
