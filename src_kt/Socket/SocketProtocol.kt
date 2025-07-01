package com.example.nekit.Socket

import java.lang.ref.WeakReference

import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port


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
 * Protocol for a higher-level socket abstraction, which internally uses a [RawTCPSocketProtocol].
 *
 * Implementations are not required to be inherently thread-safe. It is generally expected that
 * methods on an instance are called from a specific, consistent queue or coroutine context,
 * unless the implementation explicitly states otherwise.
 */
interface SocketProtocol {
    /**
     * The delegate to handle socket events. Use WeakReference to avoid retain cycles.
     */
    var delegate: WeakReference<SocketDelegate?>?

    /**
     * The underlying raw TCP socket that transmits data.
     * This should be non-null after the socket is properly initialized or connected.
     */
    val rawSocket: RawTCPSocketProtocol?

    /**
     * The current connection status of the socket.
     */
    val status: SocketStatus

    /**
     * True if the socket is currently connected.
     */
    val isConnected: Boolean

    /**
     * Indicates if the socket is disconnected (i.e., in [SocketStatus.CLOSED] or [SocketStatus.INVALID] state).
     */
    val isDisconnected: Boolean
        get() = (status == SocketStatus.CLOSED || status == SocketStatus.INVALID)

    /**
     * The source IP address of the socket.
     */
    val sourceIPAddress: IPAddress?

    /**
     * The source port of the socket.
     */
    val sourcePort: Port?

    /**
     * The destination (remote) IP address.
     */
    val destinationIPAddress: IPAddress?

    /**
     * The destination (remote) port.
     */
    val destinationPort: Port?

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
     * 
     * This simplified approach reads available data from the socket without complex parsing.
     * Application layer should handle delimiter-based parsing, fixed-length reading, etc.
     * This eliminates concurrency issues and simplifies the socket implementation.
     */
    fun readData()

    /**
     * Writes data to the socket. This operation is suspending.
     *
     * @param data The [ByteArray] to send.
     */
    fun write(data: ByteArray)

    /**
     * Initiates a graceful disconnect.
     */
    fun disconnect(becauseOf: Throwable? = null)

    /**
     * Forces an immediate disconnect.
     */
    fun forceDisconnect(becauseOf: Throwable? = null)
}
