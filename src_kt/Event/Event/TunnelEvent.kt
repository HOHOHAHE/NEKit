package nekit.Event.Event


import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Tunnel.Tunnel

interface TunnelEvent : Event {
    class ReceivedRequest(val tunnel: Tunnel) : TunnelEvent
    class Opened(val tunnel: Tunnel) : TunnelEvent
    class ConnectedToRemote(val tunnel: Tunnel) : TunnelEvent
    class UpdatingAdapterSocket(val tunnel: Tunnel) : TunnelEvent
    class TunnelClosed(val tunnel: Tunnel, val onServer: Any) : TunnelEvent
}