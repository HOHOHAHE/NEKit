package com.example.nekit.Socket.ProxySocket

import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Messages.ConnectSession

interface ProxySocketInterface : SocketProtocol {
    override val rawSocket: RawTCPSocketProtocol
    override val isConnected: Boolean
    val isCancelled: Boolean
    val session: ConnectSession?

    fun openSocket()
    override fun disconnect(becauseOf: Throwable?)
    override fun forceDisconnect(becauseOf: Throwable?)
    override suspend fun readData()
    fun respondTo(adapter: AdapterSocket)
    fun didRead(data: ByteArray, from: RawTCPSocketProtocol)
    fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol)
    override fun toString(): String
}