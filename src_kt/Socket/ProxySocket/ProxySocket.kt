import java.lang.ref.WeakReference
import java.io.IOException

// Assuming SocketProtocol.kt, RawTCPSocketProtocol.kt, RawTCPSocketDelegate.kt,
// ConnectSession.kt (Messages), Observer.kt, ProxySocketEvent.kt (Event), ObserverFactory.kt (Event),
// AdapterSocket.kt (AdapterSocket), EventSource.kt (Messages) are available.

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
                // println("DEBUG: ProxySocket($this): Status changing from $field to $value")
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
        println("INFO: ProxySocket created with rawSocket: $rawSocket. Initial status: $_status")
    }

    /**
     * Called by subclasses to indicate that the ProxySocket is now active and should
     * begin processing data from the client (e.g., start reading initial requests).
     */
    open fun openSocket() {
        if (isCancelled) {
            println("WARN: ProxySocket: openSocket called on a cancelled socket.")
            return
        }
        // _status is already ESTABLISHED by default from init.
        // This call might just be for signaling.
        println("INFO: ProxySocket: openSocket() called. Status: $_status. Ready to process client data.")
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
            println("WARN: ProxySocket: respondTo called on a cancelled socket.")
            return
        }
        println("INFO: ProxySocket: respondTo called with adapter $adapter. Client should be notified of success.")
        observer?.signal(ProxySocketEvent.AskedToResponseTo(adapter, this))
        // Subclasses (HTTPProxySocket, SOCKS5ProxySocket) will override this to send
        // protocol-specific success messages (e.g., "HTTP/1.1 200 OK" or SOCKS5 success reply)
        // and then transition to a data forwarding state.
    }

    // Implementation of SocketProtocol methods, delegating to rawSocket
    override fun readData() {
        if (isCancelled) return
        println("DEBUG: ProxySocket: readData() called, delegating to rawSocket.")
        rawSocket.readData()
    }

    override fun write(data: ByteArray) { // Could be suspend
        if (isCancelled) {
            // Optionally throw IOException("Socket cancelled, cannot write.")
            println("WARN: ProxySocket: write() called on a cancelled socket. Data not sent.")
            return
        }
        println("DEBUG: ProxySocket: write(${data.size} bytes) called, delegating to rawSocket.")
        // TODO: Consider if this should be suspend fun write(data: ByteArray) and call rawSocket.write in a coroutine
        // For now, direct call, assuming RawTCPSocketProtocol.write handles its own asynchronicity or suspension.
        // If rawSocket.write is suspending:
        // CoroutineScope(Dispatchers.IO).launch { rawSocket.write(data) } // Manage scope properly
        rawSocket.write(data) // Assuming rawSocket.write is itself suspend or handles async internally
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return // Already fully closed and cancelled

        println("INFO: ProxySocket: disconnect called. Error: ${becauseOf?.message}")
        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        session?.disconnected(becauseOf = becauseOf, by = EventSource.PROXY)
        observer?.signal(ProxySocketEvent.DisconnectCalled(this))
        rawSocket.disconnect() // Graceful disconnect of the client connection
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return

        println("INFO: ProxySocket: forceDisconnect called. Error: ${becauseOf?.message}")
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
        println("INFO: ProxySocket: Underlying raw socket disconnected.")
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
        println("DEBUG: ProxySocket: Raw data read (${data.size} bytes). Subclass should process.")
        observer?.signal(ProxySocketEvent.ReadData(data, this))
        // Subclass override of this method will parse `data` and then potentially call
        // `this.delegate?.didReceive(parsedSession, this)` or `this.delegate?.didRead(processedData, this)`
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        // Base ProxySocket signals an event. Subclasses forward to their delegate if in data forwarding phase.
        println("DEBUG: ProxySocket: Raw data written (acked). Data size: ${data?.size ?: "N/A"}.")
        observer?.signal(ProxySocketEvent.WroteData(data, this))
        // Subclass override might call `this.delegate?.didWrite(data, this)` if in data forwarding phase.
    }

    override fun didConnect(socket: RawTCPSocketProtocol) {
        // This callback should ideally not be called for a ProxySocket's rawSocket,
        // as ProxySocket is created with an already connected client socket.
        // If it were called, it might indicate an unexpected state or a reconnect on the raw socket.
        println("WARN: ProxySocket: didConnect called by rawSocket. This is unexpected for a ProxySocket.")
        // The original Swift code had an empty implementation here with a note: "This never happens for ProxySocket".
        // If it does happen, ensure status reflects reality.
        // _status = SocketStatus.ESTABLISHED // Re-affirm if necessary
        // observer?.signal(...)
        // delegate?.didBecomeReadyToForward(this) // Or similar if appropriate
    }

    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        // Called by underlying rawSocket for errors.
        println("ERROR: ProxySocket: Raw socket error: ${error.message}")
        observer?.signal(ProxySocketEvent.ErrorOccurred(error, this))
        // Errors on the raw socket usually lead to disconnection.
        forceDisconnect(becauseOf = error)
    }
}
