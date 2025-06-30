package com.example.nekit.Socket

import java.lang.ref.WeakReference

import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketDelegate

/**
 * Delegate interface for handling events from a [SocketProtocol] instance.
 * Callbacks are expected to be invoked on a specific dispatcher context,
 * as defined by the [SocketProtocol] implementation.
 */
interface SocketDelegate {
    /**
     * Called when an adapter socket successfully connects to its remote destination.
     * @param socket The [SocketProtocol] that connected.
     */
    fun didConnect(socket: SocketProtocol)

    /**
     * Called when any socket (adapter or proxy) disconnects.
     * This is the final event for a socket instance.
     * @param socket The [SocketProtocol] instance that disconnected.
     */
    fun didDisconnect(socket: SocketProtocol)

    /**
     * Called when data has been read from the socket.
     * @param data The [ByteArray] containing the data read.
     * @param from The [SocketProtocol] instance from which data was read.
     */
    fun didRead(data: ByteArray, from: SocketProtocol)

    /**
     * Called when data written to the socket has been successfully sent.
     * @param data The [ByteArray] that was written (may be null if not provided by implementation).
     * @param by The [SocketProtocol] instance through which data was written.
     */
    fun didWrite(data: ByteArray?, by: SocketProtocol)

    /**
     * Called when the socket is ready to forward data in both directions.
     * This is particularly relevant for proxy setups after initial handshakes or connections.
     * @param socket The [SocketProtocol] instance that is ready.
     */
    fun didBecomeReadyToForward(socket: SocketProtocol)

    /**
     * Called by a [ProxySocket] when it has received enough information from a client
     * to establish a new outgoing connection (represented by a [ConnectSession]).
     * @param session The [ConnectSession] containing details for the outgoing connection.
     * @param from The [ProxySocket] that received the client request.
     */
    fun didReceive(session: ConnectSession, from: ProxySocket)

    /**
     * Called when an [AdapterSocket] decides it needs to be replaced by a new one,
     * for example, due to a change in routing or protocol for a connection.
     * @param newAdapter The new [AdapterSocket] that should take over.
     */
    fun updateAdapter(newAdapter: AdapterSocket)

    /**
     * Called when an error occurs on the socket (e.g., connection failed, read/write error).
     * After a significant error, `didDisconnect` will likely follow.
     *
     * @param error The error that occurred.
     * @param on The socket where the error occurred.
     */
    fun didErrorOccur(error: Throwable, on: SocketProtocol)
}