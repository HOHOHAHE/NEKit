package nekit.Event.Event


import nekit.ProxyServer.ProxyServer
import nekit.Socket.ProxySocket.ProxySocketInterface

interface ProxyServerEvent : Event {
    class Started(val server: ProxyServer) : ProxyServerEvent
    class Stopped(val server: ProxyServer) : ProxyServerEvent
    class NewSocketAccepted(val socket: ProxySocketInterface, val onServer: ProxyServer) : ProxyServerEvent
    class TunnelClosed(val server: ProxyServer) : ProxyServerEvent
}