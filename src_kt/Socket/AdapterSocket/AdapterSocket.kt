import java.lang.ref.WeakReference

import org.slf4j.LoggerFactory

// Assuming SocketProtocol.kt, RawTCPSocketProtocol.kt, ConnectSession.kt, Observer.kt, etc. are available.

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
    override var rawSocket: RawTCPSocketProtocol?, // Made var and nullable, subclasses might set it up. Or pass in constructor.
                                                 // Let's make it a constructor param for clarity that subclasses provide it.
    observe: Boolean = true
) : SocketProtocol, RawTCPSocketDelegate {

    private val logger = LoggerFactory.getLogger(this::class.java) // Logger for specific subclass instance

    // To be more robust, subclasses should provide the rawSocket in their constructor.
    // constructor(initialRawSocket: RawTCPSocketProtocol, observe: Boolean = true) : this(observe) {
    //     this.rawSocket = initialRawSocket
    // }
    // For now, let's assume it can be null initially and set by openSocketWith or subclasses before use.
    // If openSocketWith requires it, then it must be non-null by then.
    // The Swift code `socket?.delegate = self` in openSocketWith means it expects socket to be there.
    // Let's make it a lateinit var that subclasses must initialize or openSocketWith must handle.
    // Given subclasses like DirectAdapter create it, lateinit is appropriate.
    // However, to match `override var rawSocket` from interface, it needs to be settable or constructor arg.
    // The interface made it `val rawSocket: RawTCPSocketProtocol?`. Let's stick to that.
    // This means subclasses should initialize it. The base class can't guarantee non-null without it.
    // For now, making it nullable as per interface. Subclasses will make it non-null.

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

        val currentRawSocket = rawSocket ?: run {
            // This case should ideally not happen if subclasses correctly initialize rawSocket.
            // Or, if this base class was responsible for creating a default rawSocket.
            logger.error("rawSocket is null in openSocketWith for session: {}. Cannot proceed.", session)
            _status = SocketStatus.CLOSED // Mark as closed/failed
            delegate?.get()?.didDisconnect(this) // Notify delegate
            return
        }

        currentRawSocket.delegate = WeakReference(this) // AdapterSocket itself is the delegate for its rawSocket
        _status = SocketStatus.CONNECTING
        // The actual connection attempt (e.g., rawSocket.connectTo(...)) is the responsibility of the subclass's
        // override of openSocketWith, typically after calling super.openSocketWith().
    }


    override fun readData() {
        if (isCancelled) return
        rawSocket?.readData()
    }

    override fun write(data: ByteArray) {
        if (isCancelled) return
        // TODO: Consider making RawTCPSocketProtocol.write suspend and call it in a coroutine.
        // For now, direct call as per original SocketProtocol interface.
        rawSocket?.write(data)
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_status == SocketStatus.CLOSED || _status == SocketStatus.DISCONNECTING) return

        _status = SocketStatus.DISCONNECTING
        _cancelled = true // Mark as cancelled by local action
        if (::_session.isInitialized) { // Check if session was initialized
            session.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
        }
        observer?.signal(AdapterSocketEvent.DisconnectCalled(this))
        rawSocket?.disconnect() // Graceful disconnect of underlying raw socket
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
         if (_status == SocketStatus.CLOSED && _cancelled) return // Already hard closed and cancelled

        _status = SocketStatus.DISCONNECTING // Intermediate state before CLOSED
        _cancelled = true
        if (::_session.isInitialized) {
            session.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
        }
        observer?.signal(AdapterSocketEvent.ForceDisconnectCalled(this))
        rawSocket?.forceDisconnect()
        // If rawSocket?.forceDisconnect() does not synchronously call didDisconnectWith,
        // we might need to manually set status to .CLOSED and call delegate here.
        // However, usually forceDisconnect should lead to didDisconnectWith callback.
        // For safety, if it's truly immediate and might not callback if already somewhat disconnected:
        if (rawSocket?.isConnected == false && _status != SocketStatus.CLOSED) {
             _status = SocketStatus.CLOSED
             delegate?.get()?.didDisconnect(this)
        }
    }

    // MARK: RawTCPSocketDelegate Implementation
    // These methods are called by the underlying rawSocket.

    override fun didDisconnect(socket: RawTCPSocketProtocol) { // Renamed from didDisconnectWith for clarity
        _status = SocketStatus.CLOSED
        _cancelled = true // Ensure cancelled is true if disconnected for any reason
        observer?.signal(AdapterSocketEvent.Disconnected(this))
        val currentDelegate = delegate?.get()
        delegate = null // Clear delegate to break potential cycles and prevent further calls
        currentDelegate?.didDisconnect(this) // Notify our own delegate
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        // Base AdapterSocket signals an event. Subclasses decide if/how to pass data to SocketDelegate.
        observer?.signal(AdapterSocketEvent.ReadData(data, this))
        // Typically, a subclass would process this data (e.g., decrypt, parse protocol)
        // and then call `this.delegate?.didRead(processedData, this)`
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        // Base AdapterSocket signals an event. Subclasses decide if/how to notify SocketDelegate.
        observer?.signal(AdapterSocketEvent.WroteData(data, this))
        // `this.delegate?.didWrite(data, this)` could be called here if appropriate for all adapters.
    }

    override fun didConnect(socket: RawTCPSocketProtocol) { // Renamed from didConnectWith
        _status = SocketStatus.ESTABLISHED
        observer?.signal(AdapterSocketEvent.Connected(this))
        delegate?.get()?.didConnect(this) // Pass `self` (the AdapterSocket) not the raw socket
    }

    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        // Added to RawTCPSocketDelegate for better error propagation
        logger.error("Raw socket error on {}: {}", on, error.message, error)
        observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
        // Decide if this error should lead to disconnection
        // this.delegate?.didErrorOccur(error, this) // If SocketDelegate also has didErrorOccur
        forceDisconnect(becauseOf = error) // Often, raw socket errors are fatal
    }
}
