package com.example.nekit.Event.Event


import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Socket.ProxySocket.ProxySocketInterface

interface ProxyServerEvent : Event {
    class Started(val server: ProxyServer) : ProxyServerEvent
    class Stopped(val server: ProxyServer) : ProxyServerEvent
    class NewSocketAccepted(val socket: ProxySocketInterface, val onServer: ProxyServer) : ProxyServerEvent
    class TunnelClosed(val server: ProxyServer) : ProxyServerEvent
}