package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.SecureHTTPAdapter
import com.example.nekit.Utils.HTTPAuthentication

class SecureHTTPAdapterFactory(
    val serverHost: String,
    val serverPort: Int,
    val auth: HTTPAuthentication?
) : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SecureHTTPAdapter(
            serverHost = serverHost,
            serverPort = serverPort,
            auth = { _ -> auth?.let { Pair(it.username, it.password) } ?: Pair("", "") }
        )
    }
}