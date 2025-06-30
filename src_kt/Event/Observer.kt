package com.example.nekit.Event

import com.example.nekit.Event.Event.Event
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Tunnel.Tunnel

interface Observer<T : Event> {
    fun signal(event: T)
}