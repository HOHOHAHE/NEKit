package com.example.nekit.ProxyServer

import com.example.nekit.Socket.ProxySocket.ProxySocketInterface

interface ProxyServerInterface {
    suspend fun didAcceptNewSocket(socket: ProxySocketInterface)
}