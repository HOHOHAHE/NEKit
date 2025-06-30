package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.RejectAdapter

class RejectAdapterFactory(
    val delay: Int
) : AdapterFactory() {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return RejectAdapter(delay)
    }
}
