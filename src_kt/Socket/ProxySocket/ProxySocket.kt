package com.example.nekit.Socket.ProxySocket

import java.lang.ref.WeakReference
import java.io.IOException
import org.slf4j.LoggerFactory // Added import

import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Socket.SocketDelegate
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.RawSocket.RawTCPSocketDelegate
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Event.Observer
import com.example.nekit.Event.Event.ProxySocketEvent
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Messages.EventSource
import com.example.nekit.Socket.SocketStatus

/**
 * Base class for proxy sockets, representing the server-side of a connection that handles
 * an incoming client request. It uses an underlying [RawTCPSocketProtocol] for communication
 * with the client.
 *
 * Subclasses (e.g., HTTPProxySocket, SOCKS5ProxySocket) implement specific proxy protocol logic.
 *
 * @param clientRawSocket The already connected [RawTCPSocketProtocol] instance representing the client connection.
 * @param observe Boolean flag to enable event observation for this socket.
 */
@Suppress(" এটাকে ") // For _status property if linter flags it
open class ProxySocket(
    // Changed from `socket: RawTCPSocketProtocol!` to a constructor parameter.
    // It's fundamental and should be non-null.
    override val rawSocket: RawTCPSocketProtocol,
    observe: Boolean = true
) : SocketProtocol, RawTCPSocketDelegate {
    private val logger = LoggerFactory.getLogger(this::class.java) // Logger for specific subclass instance

    /**
     * The [ConnectSession] derived from the client's request.
     * This is typically populated by a subclass after parsing the initial request.
     */
    var session: ConnectSession? = null
        protected set // Subclasses can set it

    var observer: Observer<ProxySocketEvent>? = null // Assuming ProxySocketEvent.kt

    private var _cancelled = false
    val isCancelled: Boolean
        get() = _cancelled

    // SocketProtocol Implementation
    override var delegate: WeakReference<SocketDelegate?>? = null

    // Initialized to ESTABLISHED as per Swift, implying ProxySocket is created with an already connected raw socket.
    protected var _status: SocketStatus = SocketStatus.ESTABLISHED
        set(value) {
            if (field != value) {
                // logger.trace("Status changing from {} to {}", field, value) // Example if trace was desired
                field = value
            }
        }
    override val status: SocketStatus
        get() = _status


    override fun toString(): String {
        val sessionInfo = session?.let { "host:${it.host} port:${it.port}" } ?: "uninitialized session"
        return "<${typeName} $sessionInfo status:$status>"
    }

    init {
        // Set self as the delegate for the provided raw client socket to handle its events.
        this.rawSocket.delegate = WeakReference(this as RawTCPSocketDelegate) // Cast needed if 'this' is not yet fully typed

        if (observe) {
            // Assuming ObserverFactory.kt and ProxySocketEvent.kt are available
            observer = ObserverFactory.currentFactory?.getObserverForProxySocket(this)
        }
        logger.info("Created with rawSocket: {}. Initial status: {}", rawSocket, _status)
    }

    /**
     * Called by subclasses to indicate that the ProxySocket is now active and should
     * begin processing data from the client (e.g., start reading initial requests).
     */
    open fun openSocket() {
        if (isCancelled) {
            logger.warn("openSocket called on a cancelled socket.")
            return
        }
        // _status is already ESTABLISHED by default from init.
        // This call might just be for signaling.
        logger.info("openSocket() called. Status: {}. Ready to process client data.", _status)
        observer?.signal(ProxySocketEvent.SocketOpened(this))
        // Typically, a subclass would call this.readData() here to start reading the client's request.
    }

    /**
     * Called by the Tunnel when the corresponding AdapterSocket has successfully connected
     * to the remote target. The ProxySocket should now send the appropriate success
     * response back to the client.
     *
     * @param adapter The AdapterSocket that has connected to the target.
     */
    open fun respondTo(adapter: AdapterSocket) {
        if (isCancelled) {
            logger.warn("respondTo called on a cancelled socket for session: {}", session)
            return
        }
        logger.info("respondTo called with adapter {} for session {}. Client should be notified of success.", adapter, session)
        observer?.signal(ProxySocketEvent.AskedToResponseTo(adapter, this))
        // Subclasses (HTTPProxySocket, SOCKS5ProxySocket) will override this to send
        // protocol-specific success messages (e.g., "HTTP/1.1 200 OK" or SOCKS5 success reply)
        // and then transition to a data forwarding state.
    }

    // Implementation of SocketProtocol methods, delegating to rawSocket
    override fun readData() {
        if (isCancelled) return
        logger.debug("readData() called for session {}, delegating to rawSocket.", session)
        rawSocket.readData()
    }

    override fun write(data: ByteArray) { // Could be suspend
        if (isCancelled) {
            // Optionally throw IOException("Socket cancelled, cannot write.")
            logger.warn("write() called on a cancelled socket for session {}. Data not sent.", session)
            return
        }
        logger.debug("write({} bytes) called for session {}, delegating to rawSocket.", data.size, session)
        // TODO: Consider if this should be suspend fun write(data: ByteArray) and call rawSocket.write in a coroutine
        // For now, direct call, assuming RawTCPSocketProtocol.write handles its own asynchronicity or suspension.
        // If rawSocket.write is suspending:
        // CoroutineScope(Dispatchers.IO).launch { rawSocket.write(data) } // Manage scope properly
        rawSocket.write(data) // Assuming rawSocket.write is itself suspend or handles async internally
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return // Already fully closed and cancelled

        logger.info("disconnect called for session {}. Error: {}", session, becauseOf?.message)
        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        session?.disconnected(becauseOf = becauseOf, by = EventSource.PROXY)
        observer?.signal(ProxySocketEvent.DisconnectCalled(this))
        rawSocket.disconnect() // Graceful disconnect of the client connection
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return

        logger.info("forceDisconnect called for session {}. Error: {}", session, becauseOf?.message)
        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        session?.disconnected(becauseOf = becauseOf, by = EventSource.PROXY)
        observer?.signal(ProxySocketEvent.ForceDisconnectCalled(this))
        rawSocket.forceDisconnect()
        // If rawSocket.forceDisconnect() is synchronous and might not trigger didDisconnect callback
        // if already somewhat disconnected, ensure local state reflects closure.
        if (!rawSocket.isConnected && _status != SocketStatus.CLOSED) {
            _status = SocketStatus.CLOSED
            // Manually trigger didDisconnect for self if raw socket's callback might not fire.
            // However, RawTCPSocketDelegate.didDisconnect should be the one to finalize.
            // For now, rely on the delegate callback.
        }
    }

    // MARK: RawTCPSocketDelegate Implementation
    // These methods are called by the underlying rawSocket (client connection).

    override fun didDisconnect(socket: RawTCPSocketProtocol) { // Renamed from didDisconnectWith
        if (_status == SocketStatus.CLOSED) return // Avoid redundant processing
        logger.info("Underlying raw socket disconnected for session {}.", session)
        _status = SocketStatus.CLOSED
        _cancelled = true // Ensure cancelled is true
        observer?.signal(ProxySocketEvent.Disconnected(this))
        val currentDelegate = delegate?.get()
        delegate = null // Clear delegate
        currentDelegate?.didDisconnect(this) // Notify our own delegate (e.g., Tunnel)
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        // Base ProxySocket signals an event. Subclasses are responsible for parsing this data
        // (e.g., HTTP request, SOCKS handshake) and then acting upon it, which might involve
        // calling `this.delegate?.didReceive(session, this)` or forwarding data if already in that phase.
        logger.debug("Raw data read ({} bytes) for session {}. Subclass should process.", data.size, session)
        observer?.signal(ProxySocketEvent.ReadData(data, this))
        // Subclass override of this method will parse `data` and then potentially call
        // `this.delegate?.didReceive(parsedSession, this)` or `this.delegate?.didRead(processedData, this)`
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        // Base ProxySocket signals an event. Subclasses forward to their delegate if in data forwarding phase.
        logger.debug("Raw data written (acked) for session {}. Data size: {}.", session, data?.size ?: "N/A")
        observer?.signal(ProxySocketEvent.WroteData(data, this))
        // Subclass override might call `this.delegate?.didWrite(data, this)` if in data forwarding phase.
    }

    override fun didConnect(socket: RawTCPSocketProtocol) {
        // This callback should ideally not be called for a ProxySocket's rawSocket,
        // as ProxySocket is created with an already connected client socket.
        // If it were called, it might indicate an unexpected state or a reconnect on the raw socket.
        logger.warn("didConnect called by rawSocket for session {}. This is unexpected for a ProxySocket.", session)
        // The original Swift code had an empty implementation here with a note: "This never happens for ProxySocket".
        // If it does happen, ensure status reflects reality.
        // _status = SocketStatus.ESTABLISHED // Re-affirm if necessary
        // observer?.signal(...)
        // delegate?.didBecomeReadyToForward(this) // Or similar if appropriate
    }

    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        // Called by underlying rawSocket for errors.
        logger.error("Raw socket error for session {}: {}", session, error.message, error)
        observer?.signal(ProxySocketEvent.ErrorOccurred(error, this))
        // Errors on the raw socket usually lead to disconnection.
        forceDisconnect(becauseOf = error)
    }
}
