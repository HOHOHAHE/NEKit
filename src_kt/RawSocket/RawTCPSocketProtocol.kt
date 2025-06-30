package com.example.nekit.RawSocket
import java.lang.ref.WeakReference

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Delegate protocol to handle events from a RawTCPSocketProtocol instance.
 * Implementations of these methods should be mindful of the execution context (thread/coroutine dispatcher)
 * from which they are called, as specified by the RawTCPSocketProtocol implementation.
 */
interface RawTCPSocketDelegate {
    /**
     * Called when the socket disconnects, either locally or by the remote peer.
     * This should only be called once in the entire lifetime of a socket.
     * After this is called, the delegate will not receive any other events from this socket,
     * and the socket should be considered closed and ready for cleanup.
     *
     * @param socket The socket which disconnected.
     */
    fun didDisconnect(socket: RawTCPSocketProtocol)

    /**
     * Called when data has been successfully read from the socket.
     * This is typically called in response to a previous `readData()`, `readDataTo(length:)`,
     * or `readDataTo(delimiter:)` call.
     *
     * @param data The ByteArray containing the data read from the socket.
     * @param from The socket from which the data was read.
     */
    fun didRead(data: ByteArray, from: RawTCPSocketProtocol)

    /**
     * Called when data written to the socket has been successfully sent (e.g., acknowledged by the OS network stack).
     *
     * @param data The data which was written. This may be null if the socket implementation
     *             does not keep a copy of the written data for this callback (e.g., to save memory).
     * @param by The socket through which the data was written.
     */
    fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) // Data can be null if not provided by impl

    /**
     * Called when the socket successfully connects to the remote host.
     *
     * @param socket The socket that connected.
     */
    fun didConnect(socket: RawTCPSocketProtocol)

    /**
     * Called when an error occurs on the socket (e.g., connection failed, read/write error).
     * After a significant error, `didDisconnect` will likely follow.
     *
     * @param error The error that occurred.
     * @param on The socket where the error occurred.
     */
    fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) // Added for error reporting
}


/**
 * The RawTCPSocketProtocol defines the interface for a TCP socket.
 *
 * Implementations are not required to be inherently thread-safe. It is expected that
 * methods on an instance are called from a specific, consistent queue or coroutine context,
 * unless the implementation explicitly states otherwise.
 */
interface RawTCPSocketProtocol {
    /**
     * The delegate to handle socket events. Use WeakReference to avoid retain cycles
     * if the delegate might also hold a strong reference to the socket.
     */
    var delegate: WeakReference<RawTCPSocketDelegate?>? // Or RawTCPSocketDelegate? directly if lifecycle managed

    /**
     * True if the socket is currently connected, false otherwise.
     */
    val isConnected: Boolean

    /**
     * The source IP address of the socket, if bound or connected. Null otherwise.
     */
    val sourceIPAddress: IPAddress?

    /**
     * The source port of the socket, if bound or connected. Null otherwise.
     */
    val sourcePort: Port?

    /**
     * The destination (remote) IP address, if connected. Null otherwise.
     */
    val destinationIPAddress: IPAddress?

    /**
     * The destination (remote) port, if connected. Null otherwise.
     */
    val destinationPort: Port?

    /**
     * Connects to a remote host.
     * This is a suspending function as network operations can block or are asynchronous.
     *
     * @param host The hostname or IP address string of the remote host.
     * @param port The port number on the remote host.
     * @param enableTLS If true, attempts to establish a TLS connection after TCP connection.
     * @param tlsSettings Optional settings for TLS handshake (implementation-dependent).
     * @throws Exception if the connection attempt fails (e.g., host unreachable, connection refused, TLS handshake error).
     */
    @Throws(Exception::class)
    suspend fun connectTo(host: String, port: Int, enableTLS: Boolean = false, tlsSettings: Map<String, Any>? = null)
    // Changed tlsSettings key to String for common JKS/TrustManager type settings keys

    /**
     * Initiates a graceful disconnect. The socket attempts to send any queued write data
     * before closing the connection. `didDisconnect` will be called on the delegate eventually.
     */
    fun disconnect(becauseOf: Throwable? = null)

    /**
     * Forces an immediate disconnect. Any unsent data may be lost.
     * `didDisconnect` will be called on the delegate.
     */
    fun forceDisconnect(becauseOf: Throwable? = null)

    /**
     * Writes data to the socket. This is a suspending function.
     * It should only be called when the socket is connected.
     * The `delegate.didWrite` callback will be invoked upon successful transmission.
     *
     * @param data The ByteArray to send.
     * @throws IOException or other specific exceptions if the write fails.
     *
     * Warning: As per original Swift comments, some implementations might expect this to be called
     * only after the previous `didWriteData` (didWrite) delegate callback. This implies a flow control
     * mechanism managed by the user of the socket.
     */
    @Throws(Exception::class)
    suspend fun write(data: ByteArray)

    /**
     * Initiates an asynchronous read operation. Data read will be delivered via `delegate.didRead`.
     *
     * Warning: Original Swift comments imply a one-at-a-time read model: only call after the
     * previous `didReadData` (didRead) delegate callback.
     */
    suspend fun readData()

    /**
     * Initiates an asynchronous read for a specific number of bytes.
     * Data read will be delivered via `delegate.didRead`.
     *
     * @param length The exact number of bytes to read.
     * Warning: See one-at-a-time read model warning in `readData()`.
     */
    suspend fun readDataTo(length: Int)

    /**
     * Initiates an asynchronous read until a specific delimiter pattern is encountered.
     * The read data, including the delimiter, will be delivered via `delegate.didRead`.
     *
     * @param delimiter The ByteArray representing the delimiter pattern.
     * Warning: See one-at-a-time read model warning in `readData()`.
     */
    suspend fun readDataTo(delimiter: ByteArray)

    /**
     * Initiates an asynchronous read until a specific delimiter pattern is encountered,
     * up to a maximum number of bytes scanned.
     * The read data, including the delimiter if found within `maxLength`, will be delivered via `delegate.didRead`.
     * If `maxLength` is reached before finding the delimiter, the data read up to that point is delivered.
     *
     * @param delimiter The ByteArray representing the delimiter pattern.
     * @param maxLength The maximum number of bytes to read while searching for the delimiter.
     * Warning: See one-at-a-time read model warning in `readData()`.
     */
    suspend fun readDataTo(delimiter: ByteArray, maxLength: Int)
}
