package com.example.nekit.Event.Event
import com.example.nekit.Event.Event.EventType // Corrected import
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Messages.ConnectSession

sealed class AdapterSocketEvent : EventType {
    // Cases as data classes
    data class SocketOpened(val socket: AdapterSocket, val session: ConnectSession) : AdapterSocketEvent()
    data class DisconnectCalled(val socket: AdapterSocket) : AdapterSocketEvent()
    data class ForceDisconnectCalled(val socket: AdapterSocket) : AdapterSocketEvent()
    data class Disconnected(val socket: AdapterSocket) : AdapterSocketEvent()
    data class ReadData(val data: ByteArray, val socket: AdapterSocket) : AdapterSocketEvent() {
        // Override equals and hashCode for ByteArray content if necessary, data class does it by default
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
    data class WroteData(val data: ByteArray?, val socket: AdapterSocket) : AdapterSocketEvent() {
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
    data class Connected(val socket: AdapterSocket) : AdapterSocketEvent()
    data class ReadyForForward(val socket: AdapterSocket) : AdapterSocketEvent()
    data class ErrorOccurred(val error: Throwable, val socket: AdapterSocket) : AdapterSocketEvent() // Swift Error -> Kotlin Throwable

    override fun toString(): String {
        return when (this) {
            is SocketOpened -> "Adapter socket $socket starts to connect to remote with session $session."
            is DisconnectCalled -> "Disconnect is just called on adapter socket $socket."
            is ForceDisconnectCalled -> "Force disconnect is just called on adapter socket $socket."
            is Disconnected -> "Adapter socket $socket disconnected."
            is ReadData -> "Received ${data.size} bytes data on adapter socket $socket."
            is WroteData -> if (data != null) "Sent ${data.size} bytes data on adapter socket $socket." else "Sent data on adapter socket $socket."
            is Connected -> "Adapter socket $socket connected to remote."
            is ReadyForForward -> "Adapter socket $socket is ready to forward data."
            is ErrorOccurred -> "Adapter socket $socket encountered an error $error."
        }
    }
}
