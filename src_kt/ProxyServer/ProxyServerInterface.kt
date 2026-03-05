package nekit.ProxyServer

import nekit.Socket.ProxySocket.ProxySocketInterface

interface ProxyServerInterface {
    suspend fun didAcceptNewSocket(socket: ProxySocketInterface)
}