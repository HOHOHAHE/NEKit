package com.example.nekit.Socket.AdapterSocket
import java.lang.ref.WeakReference

import org.slf4j.LoggerFactory

import com.example.nekit.Socket.SocketProtocol // Corrected import
import com.example.nekit.Socket.SocketDelegate // Corrected import
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.RawSocket.RawTCPSocketDelegate
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Event.Observer
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.Socket.SocketStatus // Corrected import
import Event.Event.AdapterSocketEvent // Corrected import
import com.example.nekit.Messages.EventSource

// --- Ensure EventSource is available for ConnectSession.disconnected ---
// enum class EventSource { PROXY, ADAPTER, TUNNEL } // From ConnectSession.kt context
// ---

/**
 * Base class for adapter sockets.
 * An adapter socket is responsible for establishing and managing an outgoing connection
 * based on a [ConnectSession], using an underlying [RawTCPSocketProtocol].
 *
 * It implements [SocketProtocol] to be used by higher-level components (e.g., Tunnel)
 * and [RawTCPSocketDelegate] to handle events from its underlying raw socket.
 *
 * @property rawSocket The underlying raw TCP socket used for actual network communication.
 *                     This is typically provided by a concrete subclass.
 * @param observe Boolean flag to enable event observation for this socket.
 */
@Suppress(" इसको ") // Suppress ' इसको ' for 'var _status' if linter has issues with underscore
open class AdapterSocket(
    observe: Boolean = true
) : SocketProtocol, RawTCPSocketDelegate {

    // Removed direct rawSocket property from primary constructor.
    // It will be managed internally.
    protected var _rawSocket: com.example.nekit.RawSocket.RawTCPSocketProtocol? = null

    // Override the rawSocket property from SocketProtocol interface.
    override val rawSocket: com.example.nekit.RawSocket.RawTCPSocketProtocol?
        get() = _rawSocket

    private val logger = LoggerFactory.getLogger(this::class.java)

    // Secondary constructor to allow subclasses to provide an initial RawTCPSocketProtocol.
    constructor(initialRawSocket: com.example.nekit.RawSocket.RawTCPSocketProtocol, observe: Boolean = true) : this(observe) {
        this._rawSocket = initialRawSocket
    }

    lateinit var session: ConnectSession
        protected set // Can be set by openSocketWith, read by anyone.

    var observer: Observer<AdapterSocketEvent>? = null // Assuming AdapterSocketEvent.kt

    protected var _cancelled = false
    val isCancelled: Boolean
        get() = _cancelled

    // SocketProtocol Implementation
    override var delegate: WeakReference<SocketDelegate?>? = null

    protected var _status: SocketStatus = SocketStatus.INVALID
    override val status: SocketStatus
        get() = _status

    // For CustomStringConvertible in Swift, default is fine or:
    override fun toString(): String {
        return if (::_session.isInitialized) {
            "<${typeName} host:${session.host} port:${session.port} status:$status>"
        } else {
            "<${typeName} (uninitialized session) status:$status>"
        }
    }

    init {
        if (observe) {
            // Assuming ObserverFactory.kt and AdapterSocketEvent.kt are available
            observer = ObserverFactory.currentFactory?.getObserverForAdapterSocket(this)
        }
    }

    /**
     * Initiates the connection process for this adapter socket based on the provided session.
     * Subclasses typically override this to establish their specific type of connection (e.g., direct, HTTP proxy).
     * The base implementation here sets up the session and registers as the delegate for the rawSocket.
     *
     * Crucial: Subclasses *must* ensure `this.rawSocket` is initialized with a concrete
     * [RawTCPSocketProtocol] instance before calling `super.openSocketWith(session)` or
     * before `this.rawSocket.delegate = this` is effectively called by this method.
     *
     * @param session The connect session containing destination details.
     */
    open fun openSocketWith(session: ConnectSession) {
        if (isCancelled) {
            logger.warn("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        this.session = session
        // Assuming AdapterSocketEvent.kt is available
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        // Ensure _rawSocket is set before proceeding.
        // It must be set by a secondary constructor or a subclass's init block/method.
        val currentRawSocket = _rawSocket ?: run {
            logger.error("Internal rawSocket is null in openSocketWith for session: {}. Cannot proceed.", session)
            _status = com.example.nekit.Socket.SocketStatus.CLOSED
            delegate?.get()?.didDisconnect(this)
            return
        }

        currentRawSocket.delegate = WeakReference(this) // AdapterSocket itself is the delegate for its rawSocket
        _status = com.example.nekit.Socket.SocketStatus.CONNECTING
        // The actual connection attempt (e.g., rawSocket.connectTo(...)) is the responsibility of the subclass's
        // override of openSocketWith, typically after calling super.openSocketWith().
    }


    override fun readData() {
        if (isCancelled) return
        rawSocket?.readData()
    }

    override suspend fun write(data: ByteArray) { // Made suspend
        if (isCancelled) return
        rawSocket?.write(data) ?: throw IOException("Raw socket not initialized or cancelled, cannot write.")
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_status == com.example.nekit.Socket.SocketStatus.CLOSED || _status == com.example.nekit.Socket.SocketStatus.DISCONNECTING) return

        _status = com.example.nekit.Socket.SocketStatus.DISCONNECTING
        _cancelled = true // Mark as cancelled by local action
        if (this::session.isInitialized) { // Check if session was initialized
            session.disconnected(becauseOf = becauseOf, by = com.example.nekit.Messages.EventSource.ADAPTER)
        }
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.DisconnectCalled(this))
        rawSocket?.disconnect() // Graceful disconnect of underlying raw socket
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
         if (_status == com.example.nekit.Socket.SocketStatus.CLOSED && _cancelled) return // Already hard closed and cancelled

        _status = com.example.nekit.Socket.SocketStatus.DISCONNECTING // Intermediate state before CLOSED
        _cancelled = true
        if (this::session.isInitialized) {
            session.disconnected(becauseOf = becauseOf, by = com.example.nekit.Messages.EventSource.ADAPTER)
        }
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.ForceDisconnectCalled(this))
        rawSocket?.forceDisconnect()
        // If rawSocket?.forceDisconnect() does not synchronously call didDisconnectWith,
        // we might need to manually set status to .CLOSED and call delegate here.
        // However, usually forceDisconnect should lead to didDisconnectWith callback.
        // For safety, if it's truly immediate and might not callback if already somewhat disconnected:
        if (rawSocket?.isConnected == false && _status != com.example.nekit.Socket.SocketStatus.CLOSED) {
             _status = com.example.nekit.Socket.SocketStatus.CLOSED
             delegate?.get()?.didDisconnect(this)
        }
    }

    // MARK: RawTCPSocketDelegate Implementation
    // These methods are called by the underlying rawSocket.

    override fun didDisconnect(socket: com.example.nekit.RawSocket.RawTCPSocketProtocol) { // Renamed from didDisconnectWith for clarity
        _status = com.example.nekit.Socket.SocketStatus.CLOSED
        _cancelled = true // Ensure cancelled is true if disconnected for any reason
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.Disconnected(this))
        val currentDelegate = delegate?.get()
        delegate = null // Clear delegate to break potential cycles and prevent further calls
        currentDelegate?.didDisconnect(this) // Notify our own delegate
    }

    override fun didRead(data: ByteArray, from: com.example.nekit.RawSocket.RawTCPSocketProtocol) {
        // Base AdapterSocket signals an event. Subclasses decide if/how to pass data to SocketDelegate.
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.ReadData(data, this))
        // Typically, a subclass would process this data (e.g., decrypt, parse protocol)
        // and then call `this.delegate?.didRead(processedData, this)`
    }

    override fun didWrite(data: ByteArray?, by: com.example.nekit.RawSocket.RawTCPSocketProtocol) {
        // Base AdapterSocket signals an event. Subclasses decide if/how to notify SocketDelegate.
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.WroteData(data, this))
        // `this.delegate?.didWrite(data, this)` could be called here if appropriate for all adapters.
    }

    override fun didConnect(socket: com.example.nekit.RawSocket.RawTCPSocketProtocol) { // Renamed from didConnectWith
        _status = com.example.nekit.Socket.SocketStatus.ESTABLISHED
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.Connected(this))
        delegate?.get()?.didConnect(this) // Pass `self` (the AdapterSocket) not the raw socket
    }

    override fun didErrorOccur(error: Throwable, on: com.example.nekit.RawSocket.RawTCPSocketProtocol) {
        // Added to RawTCPSocketDelegate for better error propagation
        logger.error("Raw socket error on {}: {}", on, error.message, error)
        observer?.signal(com.example.nekit.Event.Event.AdapterSocketEvent.ErrorOccurred(error, this))
        // Decide if this error should lead to disconnection
        // this.delegate?.didErrorOccur(error, this) // If SocketDelegate also has didErrorOccur
        forceDisconnect(becauseOf = error) // Often, raw socket errors are fatal
    }
}
