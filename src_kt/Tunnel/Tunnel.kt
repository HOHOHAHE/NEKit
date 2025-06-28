import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException // Added missing import
import org.slf4j.LoggerFactory // Added import

import Socket.SocketProtocol
import Socket.SocketDelegate
import Socket.ProxySocket.ProxySocket
import Socket.AdapterSocket.AdapterSocket
import Messages.ConnectSession
import Rule.RuleManager
import Socket.AdapterSocket.Factory.AdapterFactory
import Event.Event.TunnelEvent
import Event.Observer
import Event.ObserverFactory
import Tunnel.QueueFactory
import Opts.Opt

interface TunnelDelegate {
    fun tunnelDidClose(tunnel: Tunnel)
}

/**
 * The Tunnel class orchestrates data flow between a client-facing ProxySocket
 * and a destination-facing AdapterSocket.
 */
@Suppress(" इसको ") // For _status, _cancelled if linter has issues
class Tunnel(
    val proxySocket: ProxySocket
) : SocketDelegate { // Tunnel is a delegate for both its proxySocket and adapterSocket

    enum class Status(val descriptionVal: String) {
        INVALID("invalid"),
        READING_REQUEST("reading request"), // ProxySocket is reading initial request
        WAITING_TO_BE_READY("waiting to be ready"), // ConnectSession received, AdapterSocket being established
        FORWARDING("forwarding"),           // Both sockets ready, data flows
        CLOSING("closing"),
        CLOSED("closed");
        override fun toString(): String = descriptionVal
    }

    var adapterSocket: AdapterSocket? = null
        private set // Only Tunnel methods should change this

    var delegate: WeakReference<TunnelDelegate?>? = null
    var observer: Observer<TunnelEvent>? = null // Assuming TunnelEvent.kt

    private val readySignal = AtomicInteger(0) // 0: none ready, 1: one ready, 2: both ready for forward
    private val _cancelled = AtomicBoolean(false)
    private val _stopForwarding = AtomicBoolean(false) // To halt data forwarding even if sockets are up

    val isCancelled: Boolean get() = _cancelled.get()

    private var _status: Status = Status.INVALID
    val status: Status get() = _status
    val statusDescription: String get() = _status.toString()

    // Dedicated CoroutineScope for this Tunnel instance, using the processing dispatcher from QueueFactory
    // This ensures all operations on this tunnel's state are serialized if dispatcher is single-threaded.
    private val tunnelScope = CoroutineScope(SupervisorJob() + QueueFactory.getProcessingDispatcher())
    private val logger = LoggerFactory.getLogger(Tunnel::class.java)


    override fun toString(): String {
        return "<Tunnel proxySocket:$proxySocket adapterSocket:$adapterSocket status:$status>"
    }

    init {
        this.proxySocket.delegate = WeakReference(this as SocketDelegate)
        this.observer = ObserverFactory.currentFactory?.getObserverForTunnel(this)
        logger.info("Created for proxySocket: {}", proxySocket)
    }

    fun open() { // Renamed from openTunnel for Kotlin style
        if (isCancelled) {
            logger.warn("open() called on a cancelled tunnel: {}", this)
            return
        }
        logger.info("Opening for {}", proxySocket)
        // Launch on tunnel's scope to ensure state changes are synchronized if dispatcher is single-threaded
        tunnelScope.launch {
            proxySocket.openSocket() // This will trigger client request reading
            _status = Status.READING_REQUEST
            observer?.signal(TunnelEvent.Opened(this@Tunnel))
        }
    }

    fun close(error: Throwable? = null) {
        if (_cancelled.getAndSet(true)) { // Ensure close logic runs once
            return
        }
        logger.info("Closing tunnel {} due to error: {}", this, error?.message)
        tunnelScope.launch {
            _status = Status.CLOSING
            observer?.signal(TunnelEvent.CloseCalled(this@Tunnel))

            proxySocket.disconnect(becauseOf = error)
            adapterSocket?.disconnect(becauseOf = error)
            // Actual transition to CLOSED state and delegate.tunnelDidClose will happen
            // when both sockets report didDisconnect.
        }
    }

    fun forceClose(error: Throwable? = null) {
        if (_cancelled.getAndSet(true)) {
            return
        }
        logger.info("Force closing tunnel {} due to error: {}", this, error?.message)
        tunnelScope.launch {
            _status = Status.CLOSING
            _stopForwarding.set(true) // Immediately stop any forwarding attempts
            observer?.signal(TunnelEvent.ForceCloseCalled(this@Tunnel))

            proxySocket.forceDisconnect(becauseOf = error)
            adapterSocket?.forceDisconnect(becauseOf = error)
            // Similar to close(), checkStatus will eventually set to CLOSED.
        }
    }

    private val isTunnelEffectivelyClosed: Boolean
        get() = proxySocket.isDisconnected && (adapterSocket?.isDisconnected ?: true)


    // --- SocketDelegate Implementation ---
    // Methods called by ProxySocket and AdapterSocket

    override fun didReceive(session: ConnectSession, from: ProxySocket) {
        if (isCancelled) return
        logger.info("Received ConnectSession: {} from {} for tunnel {}", session, from, this)
        tunnelScope.launch {
            _status = Status.WAITING_TO_BE_READY
            observer?.signal(TunnelEvent.ReceivedRequest(session, from, this@Tunnel))

            // DNS resolution is now handled by ConnectSession's lazy ipAddress property.
            // The AdapterFactory.getAdapterFor(session) will implicitly trigger it if needed.
            openAdapter(forSession = session)
        }
    }

    private fun openAdapter(forSession: ConnectSession) { // Must run on tunnelScope
        if (isCancelled) return
        logger.info("Opening adapter for session: {} in tunnel {}", forSession, this)

        val manager = RuleManager.currentManager // Assuming RuleManager.kt
        val factory = manager.match(forSession) // This can return null

        if (factory == null) {
            logger.error("No matching rule/factory for session {} in tunnel {}. Closing tunnel.", forSession, this)
            close(IOException("No rule matched session $forSession for tunnel $this"))
            return
        }

        val newAdapter = factory.getAdapterFor(forSession)
        logger.info("Using adapter {} for session {} (via factory {}) in tunnel {}", newAdapter.typeName, forSession, factory::class.simpleName, this)
        this.adapterSocket = newAdapter
        newAdapter.delegate = WeakReference(this as SocketDelegate)
        // AdapterSocket.openSocketWith can be suspending or launch its own coroutines.
        // If it's suspending, this launch block needs to handle it.
        // My AdapterSocket.openSocketWith is not suspending, it launches internal coroutine for connect.
        newAdapter.openSocketWith(forSession)
    }

    override fun didBecomeReadyToForward(socket: SocketProtocol) {
        if (isCancelled) return
        logger.info("Socket {} became ready to forward in tunnel {}", socket, this)
        tunnelScope.launch {
            val currentReadyCount = readySignal.incrementAndGet()
            observer?.signal(TunnelEvent.ReceivedReadySignal(socket, currentReadyCount, this@Tunnel))

            if (socket is AdapterSocket) { // If adapter is ready, tell proxySocket to respond to client
                proxySocket.respondTo(socket)
            }

            if (currentReadyCount == 2) { // Both proxy and adapter are ready
                _status = Status.FORWARDING
                logger.info("Both sockets ready. Tunnel FORWARDING for session: {} in tunnel {}", proxySocket.session, this@Tunnel)
                // Start reading from both ends if not already implicitly started by their openSocket/respondTo
                proxySocket.readData()
                adapterSocket?.readData()
            }
        }
    }

    override fun didDisconnect(socket: SocketProtocol) {
        if (isCancelled && _status == Status.CLOSED) return // Already handled by close/forceClose

        logger.info("Socket {} disconnected in tunnel {}. isCancelled: {}, status: {}", socket, this, isCancelled, _status)
        tunnelScope.launch {
            if (!isCancelled) { // If not initiated by tunnel.close() itself
                _stopForwarding.set(true) // Stop forwarding attempts
                close(IOException("Socket ${socket.typeName} disconnected unexpectedly in tunnel $this")) // Close the other leg
            }
            // Check overall status after a socket disconnects
            checkAndFinalizeClose()
        }
    }

    override fun didRead(data: ByteArray, from: SocketProtocol) {
        if (_stopForwarding.get() || isCancelled || _status != Status.FORWARDING) return
        // println("DEBUG: Tunnel: Read ${data.size} bytes from ${from.typeName}. Forwarding.")
        tunnelScope.launch { // Ensure write is on the tunnel's context
            try {
                if (from === proxySocket) {
                    observer?.signal(TunnelEvent.ProxySocketReadData(data, proxySocket, this@Tunnel))
                    adapterSocket?.write(data)
                } else if (from === adapterSocket) {
                    observer?.signal(TunnelEvent.AdapterSocketReadData(data, adapterSocket!!, this@Tunnel))
                    proxySocket.write(data)
                }
            } catch (e: Exception) {
                logger.error("Error during data forwarding (read from {}) in tunnel {}: {}", from.typeName, this, e.message, e)
                close(e)
            }
        }
    }

    override fun didWrite(data: ByteArray?, by: SocketProtocol) {
        if (_stopForwarding.get() || isCancelled || _status != Status.FORWARDING) return
        // println("DEBUG: Tunnel: Wrote ${data?.size ?: "some"} bytes by ${by.typeName}. Scheduling next read.")
        tunnelScope.launch { // Ensure next read is scheduled on tunnel's context
            // Read staggering logic from Swift
            try {
                if (by === proxySocket) {
                    observer?.signal(TunnelEvent.ProxySocketWroteData(data, proxySocket, this@Tunnel))
                    // Schedule read from adapter after a delay, if Opt.forwardReadInterval > 0
                    if (Opt.FORWARD_READ_INTERVAL > 0) { // Assuming Opt.kt constant
                        delay(Opt.FORWARD_READ_INTERVAL.toLong())
                    }
                    if (isActive && !_stopForwarding.get()) adapterSocket?.readData()
                } else if (by === adapterSocket) {
                    observer?.signal(TunnelEvent.AdapterSocketWroteData(data, adapterSocket!!, this@Tunnel))
                    if (isActive && !_stopForwarding.get()) proxySocket.readData()
                }
            } catch (e: Exception) {
                logger.error("Error scheduling next read after write by {} in tunnel {}: {}", by.typeName, this, e.message, e)
                close(e)
            }
        }
    }

    override fun didConnect(adapterSocket: AdapterSocket) { // Called by AdapterSocket (self)
        if (isCancelled) return
        logger.info("Adapter socket {} connected to remote for tunnel {}.", adapterSocket, this)
        tunnelScope.launch {
            observer?.signal(TunnelEvent.ConnectedToRemote(adapterSocket, this@Tunnel))
            // Note: didBecomeReadyToForward from AdapterSocket is what triggers proxySocket.respondTo
            // This didConnect is more of an informational callback for the Tunnel itself.
        }
    }

    override fun didErrorOccur(error: Throwable, on: SocketProtocol) {
        // New callback from RawTCPSocketDelegate/SocketProtocol for explicit errors
        logger.error("Error on socket {} in tunnel {}: {}", on.typeName, this, error.message, error)
        tunnelScope.launch {
            if (!isCancelled) {
                close(error) // Trigger tunnel closure due to socket error
            }
        }
    }


    override fun updateAdapter(newAdapter: AdapterSocket) {
        if (isCancelled) return
        logger.info("Updating adapter socket in tunnel {}. Old: {}, New: {}", this, adapterSocket, newAdapter)
        tunnelScope.launch {
            observer?.signal(TunnelEvent.UpdatingAdapterSocket(adapterSocket, newAdapter, this@Tunnel))

            val oldAdapter = adapterSocket
            adapterSocket = newAdapter
            newAdapter.delegate = WeakReference(this@Tunnel as SocketDelegate)

            // If old adapter was already forwarding, new one should take over.
            // This might involve re-sending initial data if needed, or just being ready.
            // If SpeedAdapter called this, it means newAdapter is already connected and ready.
            // We need to inform our proxySocket about this readiness (if it wasn't already).
            // And potentially start reading from newAdapter.
            if (_status == Status.FORWARDING || _status == Status.WAITING_TO_BE_READY) {
                 // If proxySocket was waiting for an adapter to be ready (e.g. respondTo not called yet or called for old one)
                 // Or if already forwarding, this new adapter is now the one to use.
                 // This logic is similar to didBecomeReadyToForward for the newAdapter.
                 // For simplicity, assume newAdapter will call didBecomeReadyToForward itself.
                 // Or, if newAdapter is already "ready":
                 // readySignal might need adjustment if old adapter was counted.
                 // This part is tricky. Let's assume newAdapter will signal its readiness.
                 // If old adapter was already part of readySignal count, we might need to decrement.
                 // For now, this is simplified. SpeedAdapter logic handles calling didConnect/didBecomeReady.
            }

            oldAdapter?.delegate = null // Remove delegate from old adapter
            oldAdapter?.disconnect(IOException("Adapter replaced by SpeedAdapter")) // Disconnect old one
        }
    }

    private suspend fun checkAndFinalizeClose() { // Must be called on tunnelScope
        if (isTunnelEffectivelyClosed) {
            if (_status != Status.CLOSED) { // Ensure final actions run once
                _status = Status.CLOSED
                _cancelled.set(true) // Ensure fully cancelled
                _stopForwarding.set(true)
                logger.info("Both sockets closed. Tunnel {} is now fully CLOSED.", this)
                observer?.signal(TunnelEvent.Closed(this@Tunnel))
                delegate?.get()?.tunnelDidClose(this@Tunnel)
                delegate = null // Break cycle
                observer = null // Break cycle
                // Cancel any remaining coroutines in this tunnel's scope if it's truly done.
                // However, tunnelScope is based on QueueFactory's dispatcher, so cancelling it here
                // might affect other tunnels if not managed carefully.
                // If tunnelScope was unique per tunnel: tunnelScope.cancel("Tunnel closed")
            }
        }
    }
}
