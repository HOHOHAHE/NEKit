package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.SOCKS5Adapter

class SOCKS5AdapterFactory(
    val serverHost: String,
    val serverPort: Int
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SOCKS5Adapter(
            serverHost = serverHost,
            serverPort = serverPort
        )
    }
}
