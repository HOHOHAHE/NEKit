package com.example.nekit.Socket.AdapterSocket
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import java.io.IOException // Added import for exception

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.Socket.ProxySocket.ProxySocket
import com.example.nekit.Socket.SocketDelegate
import com.example.nekit.Event.Event.AdapterSocketEvent

/**
 * Adapter that attempts to connect through multiple underlying [AdapterSocket]s,
 * potentially with delays, and uses the first one that becomes ready for forwarding.
 * It then delegates communication to this chosen adapter.
 */
class SpeedAdapter : AdapterSocket(), SocketDelegate {

    private val speedAdapterLogger = LoggerFactory.getLogger(SpeedAdapter::class.java)

    // Managed CoroutineScope for the SpeedAdapter lifecycle
    private val speedAdapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var subAdaptersConfig: List<Pair<AdapterSocket, Int>> = emptyList() // List of (adapter, delayInMs)
        set(value) {
            // Clear previous state if adapters are reset.
            // When setting new sub-adapters, ensure any old ones are properly disconnected/cleaned up
            // This is typically done external to this setter, e.g., on SpeedAdapter disconnect.
            field = value
        }

    // Atomic counters for managing concurrent connection attempts
    private val connectingCount = AtomicInteger(0)
    private val pendingToConnectCount = AtomicInteger(0) // Number of adapters yet to start connecting (due to delay)

    // Atomic flag to control if new connections should be initiated
    private val shouldConnect = AtomicBoolean(true)

    // `speedAdapterScope` is now a class property and managed.


    // Note: The constructor of AdapterSocket (super) might initialize an observer.
    // SpeedAdapter itself might not need its own observer if it primarily acts as a delegate forwarder.

    override fun openSocketWith(session: ConnectSession) {
        // Call super.openSocketWith to set this.session.
        // The rawSocket of SpeedAdapter itself is not used for direct I/O.
        // Status management in AdapterSocket (_status) will reflect SpeedAdapter's overall state.
        super.openSocketWith(session)

        if (isCancelled) { // isCancelled is from AdapterSocket base (_cancelled)
            speedAdapterLogger.info("openSocketWith called on a cancelled adapter for session: {}", session)
            return
        }

        // Workaround for IPv6 from Swift code
        if (session.isIPv6()) {
            val errorMsg = "IPv6 not supported by this SpeedAdapter setup for session ${session}. Disconnecting."
            speedAdapterLogger.warn(errorMsg)
            handleConnectionFailure(IOException(errorMsg)) // Use common failure handler
            return
        }

        if (subAdaptersConfig.isEmpty()) {
            val errorMsg = "No sub-adapters configured for session ${session}. Disconnecting."
            speedAdapterLogger.error(errorMsg)
            handleConnectionFailure(IllegalStateException(errorMsg)) // Use common failure handler
            return
        }

        _status = SocketStatus.CONNECTING // SpeedAdapter is now trying to connect via sub-adapters
        shouldConnect.set(true)
        pendingToConnectCount.set(subAdaptersConfig.size)
        connectingCount.set(0) // Number of adapters currently in the process of connecting

        for ((adapter, delayMs) in subAdaptersConfig) {
            // Clear any previous observer from the sub-adapter as SpeedAdapter will be its delegate.
            // The sub-adapter's own observer (if any) might log events that are confusing in this context.
            adapter.observer = null // As per Swift code

            speedAdapterScope.launch {
                delay(delayMs.toLong())
                if (!shouldConnect.get()) { // If shouldConnect became false, abort launch
                    pendingToConnectCount.decrementAndGet()
                    speedAdapterLogger.debug("Aborting connection attempt for {} as shouldConnect is false.", adapter)
                    return@launch
                }

                connectingCount.incrementAndGet()
                pendingToConnectCount.decrementAndGet()
                adapter.delegate = WeakReference(this@SpeedAdapter) // SpeedAdapter handles callbacks
                try {
                    // Each sub-adapter will manage its own raw socket and connection process.
                    adapter.openSocketWith(session)
                } catch (e: Exception) {
                    speedAdapterLogger.error("Exception calling openSocketWith on sub-adapter {}: {}", adapter, e.message, e)
                    // Treat as if this sub-adapter failed to connect
                    didDisconnect(adapter) // Signal failure for this specific sub-adapter
                }
            }
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
        speedAdapterLogger.info("disconnect called for session {}. Error: {}", session, becauseOf?.message)
        speedAdapterScope.cancel("SpeedAdapter disconnected") // Cancel all coroutines in this scope
        shouldConnect.set(false) // Stop any new connection attempts
        super.disconnect(becauseOf) // Sets _cancelled, _status=DISCONNECTING, notifies session, observer

        // Ensure all sub-adapters are disconnected
        subAdaptersConfig.forEach { (adapter, _) ->
            adapter.delegate = null // Prevent further callbacks from sub-adapters
            if (adapter.status != SocketStatus.CLOSED) { // If not already closed
                adapter.disconnect(becauseOf)
            }
        }
        // The final status update and delegate notification for SpeedAdapter itself
        // will be handled by didDisconnect(socket: SocketProtocol) when all sub-adapters
        // have reported their disconnections, or if no sub-adapters were active.
        // If no sub-adapters were ever launched or active, we need to ensure the delegate is notified.
        if (connectingCount.get() == 0 && pendingToConnectCount.get() == 0 && _status != SocketStatus.ESTABLISHED) {
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didDisconnect(this)
        }
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
        speedAdapterLogger.info("forceDisconnect called for session {}. Error: {}", session, becauseOf?.message)
        speedAdapterScope.cancel("SpeedAdapter force-disconnected") // Cancel all coroutines in this scope
        shouldConnect.set(false) // Stop any new connection attempts
        super.forceDisconnect(becauseOf) // Sets _cancelled, _status=DISCONNECTING, notifies session, observer

        // Ensure all sub-adapters are force-disconnected
        subAdaptersConfig.forEach { (adapter, _) ->
            adapter.delegate = null
            if (adapter.status != SocketStatus.CLOSED) { // If not already closed
                adapter.forceDisconnect(becauseOf)
            }
        }
        if (connectingCount.get() == 0 && pendingToConnectCount.get() == 0 && _status != SocketStatus.ESTABLISHED) {
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didDisconnect(this)
        }
    }

    // --- SocketDelegate Implementation (for sub-adapters) ---

    override fun didConnect(adapterSocket: AdapterSocket) {
        // This is called by a sub-adapter when its *raw socket* connects.
        // It does not mean the sub-adapter is ready for forwarding yet (e.g. HTTP/SOCKS handshake).
        // The SpeedAdapter waits for `didBecomeReadyToForwardWith`.
        // No specific action here, just log.
        speedAdapterLogger.info("Sub-adapter {} reported raw connection (didConnect). Waiting for readyToForward.", adapterSocket)
    }

    override fun didBecomeReadyToForward(socket: SocketProtocol) {
        val successfulAdapter = socket as? AdapterSocket ?: return

        if (!shouldConnect.getAndSet(false)) {
            // Another adapter already succeeded and was chosen, or SpeedAdapter was stopped.
            // Disconnect this late-coming adapter.
            speedAdapterLogger.info("Another sub-adapter already chosen or process stopped. Disconnecting late {}.", successfulAdapter)
            successfulAdapter.delegate = null // Avoid further callbacks
            successfulAdapter.forceDisconnect()
            return
        }

        speedAdapterLogger.info("Sub-adapter {} is ready to forward for session {}. Choosing this one.", successfulAdapter, session)
        _status = SocketStatus.ESTABLISHED // SpeedAdapter itself is now considered established.
        connectingCount.decrementAndGet() // This one is no longer "connecting" but "connected"
        pendingToConnectCount.set(0) // Stop any other pending connection initiations

        // Disconnect all other adapters that might still be connecting or pending.
        val currentAdapters = ArrayList(subAdaptersConfig)
        for ((adapter, _) in currentAdapters) {
            if (adapter !== successfulAdapter) {
                adapter.delegate = null // Remove delegate to prevent further callbacks
                if (adapter.status != SocketStatus.INVALID && adapter.status != SocketStatus.CLOSED) {
                    speedAdapterLogger.info("Disconnecting other sub-adapter {}.", adapter)
                    adapter.forceDisconnect()
                }
            }
        }

        // Critical step: Update the Tunnel (or whatever uses this SpeedAdapter) to use the successful sub-adapter directly.
        // The SpeedAdapter's role as a multiplexer/tester is now done for this session.
        delegate?.get()?.updateAdapter(successfulAdapter) // Inform Tunnel to use this adapter instead of SpeedAdapter

        // The Swift code also forwards some events for the chosen adapter using SpeedAdapter's observer/delegate.
        // This seems to imply SpeedAdapter might still be in the chain, or it's just for initial notification.
        // If Tunnel replaces SpeedAdapter with successfulAdapter, these subsequent calls from SpeedAdapter are moot.
        // If SpeedAdapter remains and *delegates* I/O to successfulAdapter, its own rawSocket should become successfulAdapter.rawSocket.

        // For now, let's assume after updateAdapterWith, the SpeedAdapter is no longer the primary I/O handler.
        // The original Swift code:
        // delegate?.updateAdapterWith(newAdapter: adapterSocket)
        // adapterSocket.observer = observer // Transfer SpeedAdapter's observer
        // observer?.signal(.connected(adapterSocket)) // Signal as if SpeedAdapter connected (but with chosen adapter)
        // delegate?.didConnectWith(adapterSocket: adapterSocket) // Signal SpeedAdapter's delegate about connection
        // observer?.signal(.readyForForward(adapterSocket))
        // delegate?.didBecomeReadyToForwardWith(socket: adapterSocket)
        // delegate = nil // SpeedAdapter clears its own delegate.

        // Let's replicate the event forwarding part:
        successfulAdapter.observer = this.observer // Transfer observer
        this.observer?.signal(AdapterSocketEvent.Connected(successfulAdapter)) // Use successfulAdapter for event
        this.delegate?.get()?.didConnect(successfulAdapter) // Notify SpeedAdapter's delegate about connection (using successfulAdapter)
        this.observer?.signal(AdapterSocketEvent.ReadyForForward(successfulAdapter))
        this.delegate?.get()?.didBecomeReadyToForward(successfulAdapter)

        this.delegate = null // SpeedAdapter is done.
        speedAdapterLogger.info("Handed off to {} for session {}. SpeedAdapter is now inactive.", successfulAdapter, session)
    }

    override fun didDisconnect(socket: SocketProtocol) {
        val adapterSocket = socket as? AdapterSocket ?: return
        speedAdapterLogger.info("Sub-adapter {} disconnected for session {}.", adapterSocket, session)
        adapterSocket.delegate = null // Stop listening to this adapter

        // Only decrement connectingCount if this adapter was actually attempting to connect.
        // If it was already established and then disconnected, it's a different scenario.
        // For now, let's assume this is called on a "failed" connection attempt.
        // The original Swift code didn't differentiate.
        if (connectingCount.get() > 0) { // Safety check
            connectingCount.decrementAndGet()
        }

        val totalAttemptsRemaining = connectingCount.get() + pendingToConnectCount.get()

        if (shouldConnect.get()) { // If no adapter has successfully connected yet
            if (totalAttemptsRemaining == 0) {
                // All attempts finished, and none succeeded in calling didBecomeReadyToForward first.
                val errorMsg = "All sub-adapters failed to connect or become ready for session ${session}."
                speedAdapterLogger.error(errorMsg)
                _status = SocketStatus.CLOSED
                _cancelled = true // Mark self as cancelled as all options exhausted
                val mainDelegate = delegate?.get()
                delegate = null // Clear delegate
                mainDelegate?.didErrorOccur(IOException(errorMsg), this)
                mainDelegate?.didDisconnect(this)
            }
        } else {
            // A successful adapter was already chosen and `shouldConnect` is false, or SpeedAdapter was stopped.
            // This is just cleanup for other adapters that were racing.
            speedAdapterLogger.info("Disconnect from sub-adapter {} after one was already chosen or stopped for session {}.", adapterSocket, session)
        }
    }

    // The following SocketDelegate methods are for data transfer.
    // If SpeedAdapter hands off to a chosen sub-adapter via updateAdapterWith,
    // these methods on SpeedAdapter itself might not be called for data transfer.
    // If SpeedAdapter *remains* the primary socket and internally routes data via the chosen sub-adapter,
    // then these would need to delegate to the chosen one.
    // Given `delegate = nil` after choosing, it implies hand-off.
    // So, these are mostly for completeness or if a sub-adapter calls them before hand-off.

    override fun didRead(data: ByteArray, from: SocketProtocol) {
        // This would be called by a sub-adapter. If hand-off already happened, SpeedAdapter's delegate is null.
        // If hand-off didn't happen (e.g., error during hand-off), this data might be lost or need handling.
        speedAdapterLogger.info("didRead called from sub-adapter {} for session {}. Data size: {}. (This should ideally not happen after hand-off).", from, session, data.size)
        delegate?.get()?.didRead(data, this) // Forwarding as SpeedAdapter if still active
    }

    override fun didWrite(data: ByteArray?, by: SocketProtocol) {
        speedAdapterLogger.info("didWrite called from sub-adapter {} for session {}. (This should ideally not happen after hand-off).", by, session)
        delegate?.get()?.didWrite(data, this) // Forwarding as SpeedAdapter if still active
    }

    // These are less likely to be called on SpeedAdapter itself by sub-adapters in this model.
    override fun didReceive(session: ConnectSession, from: ProxySocket) { /* Should not be called by sub-adapters */ }
    override fun updateAdapter(newAdapter: AdapterSocket) { /* SpeedAdapter is the one calling this on its delegate */ }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "SpeedAdapter"} subAdapters:${subAdaptersConfig.size} connecting:$connectingCount pending:$pendingToConnectCount session:$sessionStr status:$status>"
    }
}
