package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.Shadowsocks.ShadowsocksAdapter

class ShadowsocksAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val method: String,
    val key: ByteArray
) : AdapterFactory() {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return ShadowsocksAdapter(
            serverHost = serverHost,
            serverPort = serverPort,
            method = method,
            key = key
        )
    }
}
