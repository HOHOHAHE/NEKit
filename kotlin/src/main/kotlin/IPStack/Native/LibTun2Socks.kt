package nekit.IPStack.Native // Assuming this package structure

/**
 * Defines callback methods that the native tun2socks stack will invoke
 * to interact with the Kotlin/Java side of the application.
 * An instance of this interface is passed to the native stack during initialization.
 */
interface LibTun2SocksStackCallbacks {
    /**
     * Called by the native tun2socks stack when it has an IP packet to be written
     * to the TUN device.
     *
     * @param protocol The IP protocol version (e.g., 4 for IPv4, 6 for IPv6).
     *                 Typically, this might be implicitly IPv4 for many tun2socks implementations,
     *                 or the packet itself contains version info.
     * @param packet The raw IP packet data.
     * @param len The length of the packet data in the buffer.
     * @return The number of bytes successfully written to the TUN device, or a negative error code.
     */
    fun writePacket(protocol: Int, packet: ByteArray, len: Int): Int

    /**
     * Called by the native tun2socks stack when a new TCP socket is initiated
     * as a result of an outgoing TCP packet from the TUN device.
     * The Kotlin side should associate this socketId with a context (e.g., a new TSTCPSocket wrapper)
     * and return a set of callbacks for this specific socket.
     *
     * @param socketId A unique identifier for the newly created TCP socket, assigned by tun2socks.
     * @param context Optional context that might have been provided by Kotlin when initiating
     *                a connection (less common for server-accepted sockets like this).
     * @return An instance of [LibTun2SocksSocketCallbacks] to handle events for this specific socket.
     *         Return null if the socket cannot be handled or an error occurs.
     */
    fun onTcpSocketCreated(socketId: Int, context: Any?): LibTun2SocksSocketCallbacks?

    /**
     * Called by the native tun2socks stack when a new UDP "socket" or association is initiated.
     * (Behavior for UDP can vary significantly between tun2socks implementations).
     *
     * @param socketId A unique identifier for the UDP association.
     * @param context Optional context.
     * @return An instance of [LibTun2SocksSocketCallbacks] for UDP events, or null.
     */
    fun onUdpSocketCreated(socketId: Int, context: Any?): LibTun2SocksSocketCallbacks?
}

/**
 * Defines callback methods that the native tun2socks stack will invoke
 * for events occurring on a specific TCP or UDP socket/association.
 */
interface LibTun2SocksSocketCallbacks {
    /**
     * Called when a TCP connection attempt (initiated by `LibTun2SocksStackInterface.connectTcp`)
     * has successfully connected to the remote host.
     *
     * @param socketId The identifier of the socket that connected.
     */
    fun onConnected(socketId: Int)

    /**
     * Called when data is received from the remote host on a socket.
     *
     * @param socketId The identifier of the socket that received data.
     * @param data The buffer containing the received data.
     * @param len The number of bytes received in the buffer.
     */
    fun onDataReceived(socketId: Int, data: ByteArray, len: Int)

    /**
     * Called when the remote end of a TCP connection has closed its sending side
     * (e.g., sent a FIN). The socket might still be writable from our side.
     * For UDP, this event might not be applicable or might signify an ICMP error.
     *
     * @param socketId The identifier of the socket whose remote end closed.
     */
    fun onRemoteClosed(socketId: Int) // Corresponds to canRead returning 0 in some APIs

    /**
     * Called when a socket that was previously unable to accept more data (write buffer full)
     * can now accept more data. This is used for flow control.
     *
     * @param socketId The identifier of the socket that can now be written to.
     */
    fun onWritePossible(socketId: Int)

    /**
     * Called when an error occurs on a specific socket.
     *
     * @param socketId The identifier of the socket where the error occurred.
     * @param errorCode A native error code (e.g., from `errno`).
     */
    fun onError(socketId: Int, errorCode: Int)
}

/**
 * Defines methods for the Kotlin/Java side to interact with and control
 * the native tun2socks stack.
 * This interface will be implemented using JNA to call native C functions.
 */
interface LibTun2SocksStackInterface {
    /**
     * Initializes the native tun2socks stack.
     * Must be called before any other stack operations.
     *
     * @param callbacks An implementation of [LibTun2SocksStackCallbacks] that the native stack
     *                  will use to communicate back to the Kotlin/Java side.
     */
    fun init(callbacks: LibTun2SocksStackCallbacks)

    /**
     * Starts the tun2socks stack.
     * This might involve setting up internal timers or other resources.
     *
     * @return True if starting was successful, false otherwise.
     */
    fun start(): Boolean

    /**
     * Stops the tun2socks stack and cleans up its resources.
     * All active connections managed by the stack will likely be terminated.
     */
    fun stop()

    /**
     * Inputs an IP packet (read from the TUN device) into the tun2socks stack for processing.
     *
     * @param packet The raw IP packet data.
     * @param len The length of the packet data in the buffer.
     */
    fun inputPacket(packet: ByteArray, len: Int)

    /**
     * Instructs the tun2socks stack to initiate an outbound TCP connection for a given socketId.
     * The `socketId` should be one that was previously reported via `onTcpSocketCreated`
     * (though some APIs might allow Kotlin to generate socketIds for client sockets too).
     * This is typically called by the TSTCPSocket wrapper when its `connectTo` method is invoked.
     *
     * @param socketId The identifier for the socket to connect.
     * @param host The hostname or IP address string of the target server.
     * @param port The port number on the target server.
     * @return True if the connection process was successfully initiated, false otherwise.
     */
    fun connectTcp(socketId: Int, host: String, port: Int): Boolean

    /**
     * Sends data over a specific TCP socket managed by the tun2socks stack.
     *
     * @param socketId The identifier of the socket to write to.
     * @param data The buffer containing the data to send.
     * @param len The number of bytes to send from the buffer.
     * @return The number of bytes successfully written (or queued), or a negative error code.
     */
    fun writeData(socketId: Int, data: ByteArray, len: Int): Int

    /**
     * Closes a specific TCP socket managed by the tun2socks stack.
     *
     * @param socketId The identifier of the socket to close.
     */
    fun closeTcp(socketId: Int)

    // TODO: Add UDP related methods if the tun2socks library supports direct UDP interaction
    // e.g., fun sendUdpData(socketId: Int, host: String, port: Int, data: ByteArray, len: Int): Int
    // e.g., fun registerUdpSocket(socketId: Int, callbacks: LibTun2SocksSocketCallbacks): Boolean
}
