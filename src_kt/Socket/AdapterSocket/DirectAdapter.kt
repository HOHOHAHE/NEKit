import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import org.slf4j.LoggerFactory // Added import

// Assuming AdapterSocket.kt, RawTCPSocketProtocol.kt, ConnectSession.kt, RawSocketFactory.kt are available.
// Assuming SocketStatus.kt, AdapterSocketEvent.kt, EventSource.kt are available.

/**
 * Adapter that connects directly to the destination specified in the [ConnectSession].
 */
open class DirectAdapter(
    // Constructor takes a rawSocket, typically provided by a factory.
    // If null is passed (e.g. for testing or if socket is set later), openSocketWith must handle it.
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket() // Default to getting one
) : AdapterSocket(initialRawSocket = initialRawSocket, observe = true) {
    // Logger specific to DirectAdapter, inherits logger from AdapterSocket if that's preferred for generic logs
    private val directAdapterLogger = LoggerFactory.getLogger(DirectAdapter::class.java)

    /**
     * If set to `true`, the adapter might attempt to resolve `session.host` again,
     * even if `session.ipAddress` is already available.
     * Currently, this property is not actively used in the connect logic below,
     * which uses `session.host` (which might be a domain or an IP string).
     * The actual resolution behavior depends on the implementation of `RawTCPSocketProtocol.connectTo`.
     */
    var resolveHost: Boolean = false // Not used in current connect logic, but part of Swift class

    /**
     * Initiates a direct connection to the host and port specified in the [ConnectSession].
     *
     * @param session The connect session containing destination details.
     */
    override fun openSocketWith(session: ConnectSession) {
        // Call super.openSocketWith to set up the session, observer, and rawSocket.delegate.
        // It expects `this.rawSocket` to be non-null.
        // Our constructor ensures rawSocket is initialized via RawSocketFactory by default.
        super.openSocketWith(session)

        if (isCancelled) {
            directAdapterLogger.info("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        val currentRawSocket = rawSocket ?: run {
            directAdapterLogger.error("Raw socket is unexpectedly null in openSocketWith for session: {}.", session)
            _status = SocketStatus.CLOSED // Mark as closed/failed
            // Notify delegate about the failure to connect.
            val error = IllegalStateException("Raw socket not available for DirectAdapter.")
            this.session.disconnected(becauseOf = error, by = EventSource.ADAPTER)
            observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
            observer?.signal(AdapterSocketEvent.Disconnected(this))
            delegate?.get()?.didErrorOccur(error, this)
            delegate?.get()?.didDisconnect(this)
            return
        }

        _status = SocketStatus.CONNECTING
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session)) // Moved from base to here, after session is set.

        directAdapterLogger.info("Attempting direct connection to {}:{}", session.host, session.port)

        // RawTCPSocketProtocol.connectTo is a suspend function.
        // AdapterSocket.openSocketWith is not currently suspend.
        // Launch connectTo in a coroutine.
        // TODO: This scope should be managed by the AdapterSocket instance, cancellable on disconnect.
        val connectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        connectionScope.launch {
            try {
                // session.host could be a domain name or an IP string.
                // The RawTCPSocketProtocol implementation is responsible for DNS resolution if needed.
                currentRawSocket.connectTo(
                    host = session.host,
                    port = session.port,
                    enableTLS = false, // DirectAdapter typically does not initiate TLS itself for non-HTTP protocols
                    tlsSettings = null
                )
                // If connectTo completes without exception, the didConnect callback (from RawTCPSocketDelegate)
                // will be triggered, which then calls super.didConnectWith and updates status.
            } catch (e: Exception) {
                directAdapterLogger.error("Connection to {}:{} failed: {}", session.host, session.port, e.message, e)
                // Ensure delegate is notified of error and disconnection.
                // The RawTCPSocketDelegate methods (didErrorOccur, didDisconnect) should handle this.
                // If connectTo throws before those are called, handle it here.
                if (_status != SocketStatus.CLOSED) { // Avoid double disconnect signals if already handled
                    _status = SocketStatus.CLOSED
                    _cancelled = true
                    this@DirectAdapter.session.disconnected(becauseOf = e, by = EventSource.ADAPTER)
                    observer?.signal(AdapterSocketEvent.ErrorOccurred(e, this@DirectAdapter))
                    observer?.signal(AdapterSocketEvent.Disconnected(this@DirectAdapter))
                    delegate?.get()?.didErrorOccur(e, this@DirectAdapter)
                    delegate?.get()?.didDisconnect(this@DirectAdapter)
                }
            }
        }
    }

    /**
     * Called when the underlying raw socket successfully connects.
     * For DirectAdapter, this means the connection is established and ready for forwarding.
     */
    override fun didConnect(socket: RawTCPSocketProtocol) {
        super.didConnect(socket) // Updates status to ESTABLISHED, signals event, calls delegate.didConnect
        // Additional signal for direct adapters: ready to forward.
        observer?.signal(AdapterSocketEvent.ReadyForForward(this))
        delegate?.get()?.didBecomeReadyToForward(this)
        directAdapterLogger.info("Connected and ready to forward for session: {}", session)
    }

    /**
     * Called by the underlying raw socket when data is read.
     * Forwards the data to this adapter's delegate.
     */
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals AdapterSocketEvent.ReadData
        // Forward data to our delegate (e.g., Tunnel)
        delegate?.get()?.didRead(data, this)
    }

    /**
     * Called by the underlying raw socket when data has been written.
     * Forwards this event to this adapter's delegate.
     */
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals AdapterSocketEvent.WroteData
        // Forward event to our delegate
        delegate?.get()?.didWrite(data, this)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "DirectAdapter"} session:$sessionStr status:$status>"
    }
}
