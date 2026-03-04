package com.example.nekit.RawSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import java.lang.ref.WeakReference

class NettyRawUDPSocket(val host: String, val port: Int) : RawUDPSocketProtocol {
    override var delegate: WeakReference<RawUDPSocketDelegate?>? = null
    override val isConnected: Boolean = false // Placeholder
    override val sourceIPAddress: IPAddress? = null // Placeholder
    override val sourcePort: Port? = null // Placeholder
    override val destinationIPAddress: IPAddress? = null // Placeholder
    override val destinationPort: Port? = null // Placeholder
    override val localAddress: IPAddress? = null // Placeholder for local IP address
    override var onDatagramReceived: ((data: ByteArray, sourceAddress: IPAddress, sourcePort: Port) -> Unit)? = null

    override fun connect() {
        // Placeholder
    }

    override fun disconnect() {
        // Placeholder
    }

    override fun write(data: ByteArray) {
        // Placeholder
    }
    override fun bind(host: String?, port: Int) {
        // Placeholder for binding UDP socket.
        // In Netty, UDP sockets are typically bound using a Bootstrap.
        // This might involve creating a DatagramChannel and associating it with the EventLoopGroup.
        // For now, it's a no-op as the host and port are already passed in the constructor.
        // A proper implementation would bind the underlying Netty channel here.
    }

    override suspend fun suspendBind(host: String?, port: Int) {
        bind(host, port)
    }
    override fun send(data: ByteArray, destinationHost: String, destinationPort: Int) {
        // Placeholder for sending UDP data.
        // This would typically involve using Netty's DatagramChannel to write data.
        // For now, it's a no-op.
    }
}