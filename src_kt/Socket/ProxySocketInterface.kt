package com.example.nekit.Socket

import com.example.nekit.RawSocket.RawTCPSocketProtocol

/**
 * Represents a proxy socket that handles communication with a client.
 * This interface defines the common behavior for different types of proxy sockets (e.g., HTTP, SOCKS5).
 */
interface ProxySocketInterface {
    /**
     * The underlying raw TCP socket connected to the client.
     */
    val rawSocket: RawTCPSocketProtocol

    /**
     * Indicates if the socket is currently connected.
     */
    val isConnected: Boolean

    /**
     * Indicates if the socket has been cancelled or is in a disconnected state.
     */
    val isCancelled: Boolean

    /**
     * The session associated with this proxy socket.
     */
    val session: com.example.nekit.Messages.ConnectSession?

    /**
     * Opens the proxy socket, typically initiating the handshake or initial data exchange.
     */
    fun openSocket()

    /**
     * Disconnects the proxy socket, optionally providing a reason for the disconnection.
     */
    fun disconnect(becauseOf: Throwable?)

    /**
     * Forces the disconnection of the proxy socket, bypassing graceful shutdown.
     */
    fun forceDisconnect(becauseOf: Throwable?)

    /**
     * Requests the proxy socket to read more data from the client.
     */
    fun readData()

    /**
     * Provides an AdapterSocket to the ProxySocket, allowing it to respond to the client.
     * This is typically called by the Tunnel after an AdapterSocket has been established.
     */
    fun respondTo(adapter: com.example.nekit.Socket.AdapterSocket.AdapterSocket)

    /**
     * Called when data is received from the underlying raw socket.
     * @param data The received data.
     * @param from The raw socket from which the data was received.
     */
    fun didRead(data: ByteArray, from: RawTCPSocketProtocol)

    /**
     * Called when data has been successfully written to the underlying raw socket.
     * @param data The data that was written (or null if not applicable).
     * @param by The raw socket to which the data was written.
     */
    fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol)

    /**
     * Returns a string representation of the proxy socket.
     */
    override fun toString(): String
}
