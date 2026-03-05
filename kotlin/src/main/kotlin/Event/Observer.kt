package nekit.Event

import nekit.Event.Event.Event
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.ProxyServer.ProxyServer
import nekit.Socket.ProxySocket.ProxySocket
import nekit.Tunnel.Tunnel

interface Observer<T : Event> {
    fun signal(event: T)
}