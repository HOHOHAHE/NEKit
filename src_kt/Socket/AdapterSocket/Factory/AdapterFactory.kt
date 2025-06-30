package com.example.nekit.Socket.AdapterSocket.Factory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket

interface AdapterFactory {
    fun getAdapter(session: ConnectSession): AdapterSocket
}
