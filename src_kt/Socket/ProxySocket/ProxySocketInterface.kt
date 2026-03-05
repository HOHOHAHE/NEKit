package nekit.Socket.ProxySocket

import nekit.RawSocket.protocol.RawTCPSocketProtocol
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.SocketProtocol
import nekit.Messages.ConnectSession

interface ProxySocketInterface : SocketProtocol {
    override val rawSocket: RawTCPSocketProtocol
    override val isConnected: Boolean
    val isCancelled: Boolean
    val session: ConnectSession?

    fun openSocket()
    override fun disconnect(becauseOf: Throwable?)
    override fun forceDisconnect(becauseOf: Throwable?)
    override fun readData()
    fun respondTo(adapter: AdapterSocket)
    fun didRead(data: ByteArray, from: RawTCPSocketProtocol)
    fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol)
    override fun toString(): String
}