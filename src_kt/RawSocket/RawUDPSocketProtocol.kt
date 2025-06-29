package com.example.nekit.RawSocket

import java.lang.ref.WeakReference
import java.net.InetSocketAddress

interface RawUDPSocketProtocol {
    var delegate: WeakReference<RawUDPSocketDelegate?>?

    /**
     * Binds the UDP socket to a specific host and port.
     *
     * @param host The host/IP address to bind to. If null or "0.0.0.0", binds to all available interfaces.
     * @param port The port to bind to. If 0, an ephemeral port will be chosen by the system.
     * @throws Exception if binding fails.
     */
    @Throws(Exception::class)
    fun bind(host: String? = null, port: Int = 0)

    /**
     * Sends UDP data to a specified destination.
     * The socket must be bound before sending.
     *
     * @param data The ByteArray to send.
     * @param destinationHost The hostname or IP address of the destination.
     * @param destinationPort The port number on the destination host.
     * @throws Exception if sending fails (e.g., socket not bound, network error).
     */
    @Throws(Exception::class)
    fun send(data: ByteArray, destinationHost: String, destinationPort: Int)

    /**
     * Closes the UDP socket and releases associated resources.
     */
    fun disconnect()

    /**
     * The local InetSocketAddress the socket is bound to.
     * Returns null if the socket is not bound.
     */
    val localAddress: InetSocketAddress?
}
