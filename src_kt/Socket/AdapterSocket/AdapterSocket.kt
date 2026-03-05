package com.example.nekit.Socket.AdapterSocket
import java.lang.ref.WeakReference

import org.slf4j.LoggerFactory

import com.example.nekit.Socket.SocketProtocol // Corrected import
import com.example.nekit.Socket.SocketDelegate // Corrected import
import com.example.nekit.RawSocket.protocol.RawTCPSocketProtocol
import com.example.nekit.RawSocket.protocol.RawTCPSocketDelegate
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Event.Observer
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.Socket.SocketStatus // Corrected import
import com.example.nekit.Event.Event.AdapterSocketEvent // Corrected import
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
abstract class AdapterSocket(
    observe: Boolean = true
) : SocketProtocol, RawTCPSocketDelegate {

 
     protected var _rawSocket: RawTCPSocketProtocol? = null
    override val rawSocket: RawTCPSocketProtocol?
        get() = _rawSocket
 
     private val logger = LoggerFactory.getLogger(this::class.java)
 
     constructor(initialRawSocket: RawTCPSocketProtocol?, observe: Boolean = true) : this(observe) {
        this._rawSocket = initialRawSocket
        this._rawSocket?.delegate = WeakReference(this)
    }
 
     var session: ConnectSession? = null
         protected set
 
     var observer: Observer<AdapterSocketEvent>? = null
 
     protected var _cancelled = false
     val isCancelled: Boolean
         get() = _cancelled
 
     // SocketProtocol Implementation
     override var delegate: WeakReference<SocketDelegate?>? = null
 
     protected var _status: SocketStatus = SocketStatus.INVALID
     override val status: SocketStatus
         get() = _status
 
     override val isConnected: Boolean
         get() = status == SocketStatus.ESTABLISHED
 
     override val sourceIPAddress: com.example.nekit.Utils.IPAddress?
         get() = rawSocket?.sourceIPAddress
     override val sourcePort: com.example.nekit.Utils.Port?
         get() = rawSocket?.sourcePort
     override val destinationIPAddress: com.example.nekit.Utils.IPAddress?
         get() = rawSocket?.destinationIPAddress
     override val destinationPort: com.example.nekit.Utils.Port?
         get() = rawSocket?.destinationPort
 
     override fun toString(): String {
         val sessionInfo = session?.let { "host:${it.host} port:${it.port}" } ?: "uninitialized session"
         return "<$typeName $sessionInfo status:$status>"
     }
 
     init {
         if (observe) {
             observer = ObserverFactory.getObserverForAdapterSocket(this)
         }
     }
 
     open fun openSocketWith(session: ConnectSession) {
        if (isCancelled) {
            logger.warn("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        this.session = session
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        val currentRawSocket = _rawSocket ?: run {
            logger.error("Internal rawSocket is null in openSocketWith for session: {}. Cannot proceed.", session)
            _status = SocketStatus.CLOSED
            delegate?.get()?.didDisconnect(this)
            return
        }

        currentRawSocket.delegate = WeakReference(this)
        _status = SocketStatus.CONNECTING
    }
 
     override fun readData() {
        if (isCancelled) return
        rawSocket?.readData()
    }
    
    // Removed readDataTo methods - complex reading logic should be handled at application layer
 
     override fun write(data: ByteArray) {
         if (isCancelled) throw java.io.IOException("Socket is cancelled.")
         rawSocket?.write(data) ?: throw java.io.IOException("Raw socket not available.")
     }
 
     override fun disconnect(becauseOf: Throwable?) {
         if (_status == SocketStatus.CLOSED || _status == SocketStatus.DISCONNECTING) return
 
         _status = SocketStatus.DISCONNECTING
         _cancelled = true
         session?.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
         observer?.signal(AdapterSocketEvent.DisconnectCalled(this))
         rawSocket?.disconnect(becauseOf)
     }
 
     override fun forceDisconnect(becauseOf: Throwable?) {
         if (_status == SocketStatus.CLOSED && _cancelled) return
 
         _status = SocketStatus.DISCONNECTING
         _cancelled = true
         session?.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
         observer?.signal(AdapterSocketEvent.ForceDisconnectCalled(this))
         rawSocket?.forceDisconnect(becauseOf)
 
         if (rawSocket?.isConnected == false && _status != SocketStatus.CLOSED) {
             _status = SocketStatus.CLOSED
             delegate?.get()?.didDisconnect(this)
         }
     }
 
     // RawTCPSocketDelegate Implementation
     override fun didDisconnect(socket: RawTCPSocketProtocol) {
         _status = SocketStatus.CLOSED
         _cancelled = true
         observer?.signal(AdapterSocketEvent.Disconnected(this))
         val currentDelegate = delegate?.get()
         delegate = null
         currentDelegate?.didDisconnect(this)
     }
 
     override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
         observer?.signal(AdapterSocketEvent.ReadData(data, this))
         delegate?.get()?.didRead(data, this)
     }
 
     override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
         observer?.signal(AdapterSocketEvent.WroteData(data, this))
         delegate?.get()?.didWrite(data, this)
     }
 
     override fun didConnect(socket: RawTCPSocketProtocol) {
         _status = SocketStatus.ESTABLISHED
         observer?.signal(AdapterSocketEvent.Connected(this))
         delegate?.get()?.didConnect(this)
         delegate?.get()?.didBecomeReadyToForward(this)
     }
 
     override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
         logger.error("Raw socket error on {}: {}", on, error.message)
         observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
         delegate?.get()?.didErrorOccur(error, this)
         forceDisconnect(becauseOf = error)
     }
 }
