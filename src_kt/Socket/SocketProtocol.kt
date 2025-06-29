package com.example.nekit.Socket

import java.lang.ref.WeakReference

import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Messages.ConnectSession


/**
 * Represents the current connection status of a socket.
 */
enum class SocketStatus {
    /** The socket is newly created and has not attempted to connect. */
    INVALID,
    /** The socket is in the process of connecting. */
    CONNECTING,
    /** The connection is established and data can be transferred. */
    ESTABLISHED,
    /** The socket is in the process of disconnecting. */
    DISCONNECTING,
    /** The socket is closed and no longer active. */
    CLOSED
}

/**
 * Delegate interface for handling events from a [SocketProtocol] instance.
 * Callbacks are expected to be invoked on a specific dispatcher context,
 * as defined by the [SocketProtocol] implementation.
 */
interface SocketDelegate {
    /**
     * Called when an adapter socket successfully connects to its remote destination.
     * @param adapterSocket The [AdapterSocket] that connected.
     */
    fun didConnect(adapterSocket: AdapterSocket)

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
}


/**
 * Protocol for a higher-level socket abstraction, which internally uses a [RawTCPSocketProtocol].
 *
 * Implementations are not required to be inherently thread-safe. It is generally expected that
 * methods on an instance are called from a specific, consistent queue or coroutine context,
 * unless the implementation explicitly states otherwise.
 */
interface SocketProtocol {
    /**
     * The underlying raw TCP socket that transmits data.
     * This should be non-null after the socket is properly initialized or connected.
     */
    val rawSocket: RawTCPSocketProtocol? // Nullable if it can be detached or not always present

    /**
     * The delegate to handle socket events. Use [WeakReference] to avoid retain cycles
     * if the delegate might also hold a strong reference to this socket.
     */
    var delegate: WeakReference<SocketDelegate?>?

    /**
     * The current connection status of the socket.
     */
    val status: SocketStatus

    /**
     * Indicates if the socket is disconnected (i.e., in [SocketStatus.CLOSED] or [SocketStatus.INVALID] state).
     */
    val isDisconnected: Boolean
        get() = (status == SocketStatus.CLOSED || status == SocketStatus.INVALID)

    /**
     * A string representation of the concrete socket type (class name).
     */
    val typeName: String
        get() = this::class.simpleName ?: "SocketProtocol"

    /**
     * A string representation of the current read status (often same as general status).
     */
    val readStatusDescription: String
        get() = status.toString()

    /**
     * A string representation of the current write status (often same as general status).
     */
    val writeStatusDescription: String
        get() = status.toString()

    /**
     * Initiates an asynchronous read operation. Data will be delivered via [SocketDelegate.didRead].
     * Implementations should respect the one-at-a-time read model if underlying raw socket requires it.
     */
    fun readData()

    /**
     * Writes data to the socket. This operation may be asynchronous.
     * Completion (or failure) will be signaled via [SocketDelegate.didWrite] or error callbacks.
     *
     * @param data The [ByteArray] to send.
     */
    fun write(data: ByteArray) // Could be suspend fun if implementations are suspending

    /**
     * Initiates a graceful disconnect. The socket attempts to send any queued write data
     * before closing. [SocketDelegate.didDisconnect] will be called eventually.
     *
     * @param becauseOf An optional [Throwable] indicating the reason for disconnection.
     */
    fun disconnect(becauseOf: Throwable? = null)

    /**
     * Forces an immediate disconnect. Any unsent data may be lost.
     * [SocketDelegate.didDisconnect] will be called.
     *
     * @param becauseOf An optional [Throwable] indicating the reason for disconnection.
     */
    fun forceDisconnect(becauseOf: Throwable? = null)
}
