package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.SpeedAdapter

class SpeedAdapterFactory(
    val factories: List<AdapterFactory>,
    val testUrl: String
) : AdapterFactory() {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return SpeedAdapter(
            factories = factories,
            testUrl = testUrl
        )
    }
}
