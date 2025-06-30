package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Socket.AdapterSocket.DirectAdapter

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket

class DirectAdapterFactory : AdapterFactory {
    override fun getAdapter(session: ConnectSession): AdapterSocket {
        return DirectAdapter()
    }
}
