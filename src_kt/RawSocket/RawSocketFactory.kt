package com.example.nekit.RawSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.SocketProtocol

interface RawSocketFactory {
    companion object {
        var currentFactory: RawSocketFactory? = null
    }
    fun getRawTCPSocket(session: ConnectSession): RawTCPSocketProtocol
}