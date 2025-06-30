package com.example.nekit.Socket.ProxySocket

import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Messages.ConnectSession

interface ProxySocketInterface : SocketProtocol {
    val rawSocket: RawTCPSocketProtocol
    val isConnected: Boolean
    val isCancelled: Boolean
    val session: ConnectSession?

    fun openSocket()
    fun disconnect(becauseOf: Throwable?)
    fun forceDisconnect(becauseOf: Throwable?)
    fun readData()
    fun respondTo(adapter: AdapterSocket)
    fun didRead(data: ByteArray, from: RawTCPSocketProtocol)
    fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol)
    override fun toString(): String
}