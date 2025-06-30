package com.example.nekit.Event.Event


import com.example.nekit.Socket.AdapterSocket.AdapterSocket

interface AdapterSocketEvent : Event {
    class SocketOpened(val socket: AdapterSocket, val session: Any) : AdapterSocketEvent
    class DisconnectCalled(val socket: AdapterSocket) : AdapterSocketEvent
    class ForceDisconnectCalled(val socket: AdapterSocket) : AdapterSocketEvent
    class Disconnected(val socket: AdapterSocket) : AdapterSocketEvent
    class ReadData(val data: ByteArray, val socket: AdapterSocket) : AdapterSocketEvent
    class WroteData(val data: ByteArray?, val socket: AdapterSocket) : AdapterSocketEvent
    class Connected(val socket: AdapterSocket) : AdapterSocketEvent
    class ErrorOccurred(val error: Throwable, val socket: AdapterSocket) : AdapterSocketEvent
    class ReadyForForward(val socket: AdapterSocket) : AdapterSocketEvent
}