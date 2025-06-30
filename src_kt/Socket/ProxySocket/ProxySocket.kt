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
abstract class ProxySocket(
    // Changed from `socket: RawTCPSocketProtocol!` to a constructor parameter.
    // It's fundamental and should be non-null.
    override val rawSocket: RawTCPSocketProtocol,
    observe: Boolean = true
) : ProxySocketInterface, RawTCPSocketDelegate {
    private val logger = LoggerFactory.getLogger(this::class.java)
 
    override var session: ConnectSession? = null
        protected set
 
    var observer: Observer<ProxySocketEvent>? = null
 
    private var _cancelled = false
    override val isCancelled: Boolean
        get() = _cancelled
 
    // SocketProtocol Implementation
    override var delegate: WeakReference<SocketDelegate?>? = null
 
    protected var _status: SocketStatus = SocketStatus.ESTABLISHED
        set(value) {
            if (field != value) {
                field = value
            }
        }
    override val status: SocketStatus
        get() = _status
 
    override val isConnected: Boolean
        get() = status == SocketStatus.ESTABLISHED
 
    override val sourceIPAddress: com.example.nekit.Utils.IPAddress?
        get() = rawSocket.sourceIPAddress
    override val sourcePort: com.example.nekit.Utils.Port?
        get() = rawSocket.sourcePort
    override val destinationIPAddress: com.example.nekit.Utils.IPAddress?
        get() = rawSocket.destinationIPAddress
    override val destinationPort: com.example.nekit.Utils.Port?
        get() = rawSocket.destinationPort
 
    override fun toString(): String {
        val sessionInfo = session?.let { "host:${it.host} port:${it.port}" } ?: "uninitialized session"
        return "<$typeName $sessionInfo status:$status>"
    }
 
    init {
        this.rawSocket.delegate = WeakReference(this)
 
        if (observe) {
            // observer = ObserverFactory.currentFactory?.getObserverForProxySocket(this)
        }
        logger.info("Created with rawSocket: {}. Initial status: {}", rawSocket, _status)
    }
 
    override fun openSocket() {
        if (isCancelled) {
            logger.warn("openSocket called on a cancelled socket.")
            return
        }
        logger.info("openSocket() called. Status: {}. Ready to process client data.", _status)
        observer?.signal(ProxySocketEvent.SocketOpened(this))
    }
 
    override fun respondTo(adapter: AdapterSocket) {
        if (isCancelled) {
            logger.warn("respondTo called on a cancelled socket for session: {}", session)
            return
        }
        logger.info("respondTo called with adapter {} for session {}. Client should be notified of success.", adapter, session)
        observer?.signal(ProxySocketEvent.AskedToResponseTo(adapter, this))
    }
 
    override suspend fun readData() {
        if (isCancelled) return
        logger.debug("readData() called for session {}, delegating to rawSocket.", session)
        rawSocket.readData()
    }
 
    override suspend fun readDataTo(length: Int) {
        if (isCancelled) return
        rawSocket.readDataTo(length)
    }
 
    override suspend fun readDataTo(delimiter: ByteArray) {
        if (isCancelled) return
        rawSocket.readDataTo(delimiter)
    }
 
    override suspend fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        if (isCancelled) return
        rawSocket.readDataTo(delimiter, maxLength)
    }
 
    override suspend fun write(data: ByteArray) {
        if (isCancelled) throw IOException("Socket is cancelled.")
        logger.debug("write({} bytes) called for session {}, delegating to rawSocket.", data.size, session)
        rawSocket.write(data)
    }
 
    override fun disconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
 
        logger.info("disconnect called for session {}. Error: {}", session, becauseOf?.message)
        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        session?.disconnected(becauseOf = becauseOf, by = EventSource.PROXY)
        observer?.signal(ProxySocketEvent.DisconnectCalled(this))
        rawSocket.disconnect(becauseOf)
    }
 
    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
 
        logger.info("forceDisconnect called for session {}. Error: {}", session, becauseOf?.message)
        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        session?.disconnected(becauseOf = becauseOf, by = EventSource.PROXY)
        observer?.signal(ProxySocketEvent.ForceDisconnectCalled(this))
        rawSocket.forceDisconnect(becauseOf)
 
        if (!rawSocket.isConnected && _status != SocketStatus.CLOSED) {
            _status = SocketStatus.CLOSED
            delegate?.get()?.didDisconnect(this)
        }
    }
 
    // RawTCPSocketDelegate Implementation
    override fun didDisconnect(socket: RawTCPSocketProtocol) {
        if (_status == SocketStatus.CLOSED) return
        logger.info("Underlying raw socket disconnected for session {}.", session)
        _status = SocketStatus.CLOSED
        _cancelled = true
        observer?.signal(ProxySocketEvent.Disconnected(this))
        val currentDelegate = delegate?.get()
        delegate = null
        currentDelegate?.didDisconnect(this)
    }
 
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        logger.debug("Raw data read ({} bytes) for session {}. Subclass should process.", data.size, session)
        observer?.signal(ProxySocketEvent.ReadData(data, this))
        // Subclass override will handle this
    }
 
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        logger.debug("Raw data written (acked) for session {}. Data size: {}.", session, data?.size ?: "N/A")
        observer?.signal(ProxySocketEvent.WroteData(data, this))
        delegate?.get()?.didWrite(data, this)
    }
 
    override fun didConnect(socket: RawTCPSocketProtocol) {
        logger.warn("didConnect called by rawSocket for session {}. This is unexpected for a ProxySocket.", session)
    }
 
    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        logger.error("Raw socket error for session {}: {}", session, error.message, error)
        observer?.signal(ProxySocketEvent.ErrorOccurred(error, this))
        delegate?.get()?.didErrorOccur(error, this)
        forceDisconnect(becauseOf = error)
    }
}
