package com.example.nekit.Event.Event


import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Tunnel.Tunnel

interface TunnelEvent : Event {
    class ReceivedRequest(val tunnel: Tunnel) : TunnelEvent
    class Opened(val tunnel: Tunnel) : TunnelEvent
    class ConnectedToRemote(val tunnel: Tunnel) : TunnelEvent
    class UpdatingAdapterSocket(val tunnel: Tunnel) : TunnelEvent
    class TunnelClosed(val tunnel: Tunnel, val onServer: Any) : TunnelEvent
}