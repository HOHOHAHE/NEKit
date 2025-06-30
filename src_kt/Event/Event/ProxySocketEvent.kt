package com.example.nekit.Event.Event


import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.ProxySocket.ProxySocket

interface ProxySocketEvent : Event {
    class SocketOpened(val socket: ProxySocket) : ProxySocketEvent
    class AskedToResponseTo(val adapter: Any, val socket: ProxySocket) : ProxySocketEvent
    class ReadData(val data: ByteArray, val socket: ProxySocket) : ProxySocketEvent
    class WroteData(val data: ByteArray?, val socket: ProxySocket) : ProxySocketEvent
    class DisconnectCalled(val socket: ProxySocket) : ProxySocketEvent
    class ForceDisconnectCalled(val socket: ProxySocket) : ProxySocketEvent
    class Disconnected(val socket: ProxySocket) : ProxySocketEvent
    class ErrorOccurred(val error: Throwable, val socket: ProxySocket) : ProxySocketEvent
    class ReadyForForward(val socket: ProxySocket) : ProxySocketEvent
    class ReceivedRequest(val socket: ProxySocket) : ProxySocketEvent
}