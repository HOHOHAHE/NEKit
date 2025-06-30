package com.example.nekit.RawSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.SocketProtocol

interface RawSocketFactory {
    fun getRawTCPSocket(session: ConnectSession): RawTCPSocketProtocol
}