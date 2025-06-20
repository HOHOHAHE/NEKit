import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

// Assuming AdapterSocket.kt, SocketDelegate.kt, RawTCPSocketProtocol.kt, ConnectSession.kt,
// QueueFactory.kt (placeholders), IPAddress.kt, Port.kt, SocketStatus.kt, AdapterSocketEvent.kt are available.

/**
 * Adapter that attempts to connect through multiple underlying [AdapterSocket]s,
 * potentially with delays, and uses the first one that becomes ready for forwarding.
 * It then delegates communication to this chosen adapter.
 */
class SpeedAdapter : AdapterSocket(initialRawSocket = null /* SpeedAdapter orchestrates, doesn't use its own rawSocket directly for I/O */), SocketDelegate {

    var subAdaptersConfig: List<Pair<AdapterSocket, Int>> = emptyList() // List of (adapter, delayInMs)
        set(value) {
            // Clear previous state if adapters are reset
            // TODO: Ensure proper cleanup if adapters are changed mid-operation (not typical)
            field = value
        }

    // Atomic counters for managing concurrent connection attempts
    private val connectingCount = AtomicInteger(0)
    private val pendingToConnectCount = AtomicInteger(0) // Number of adapters yet to start connecting (due to delay)

    // Atomic flag to control if new connections should be initiated
    private val shouldConnect = AtomicBoolean(true)

    // Scope for launching delayed connection attempts and managing this adapter's lifecycle
    // TODO: This scope should be managed (e.g. cancellable by a disconnect call)
    private val speedAdapterScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


    // Note: The constructor of AdapterSocket (super) might initialize an observer.
    // SpeedAdapter itself might not need its own observer if it primarily acts as a delegate forwarder.

    override fun openSocketWith(session: ConnectSession) {
        // Call super.openSocketWith to set this.session.
        // The rawSocket of SpeedAdapter itself is not used for direct I/O.
        // Status management in AdapterSocket (_status) will reflect SpeedAdapter's overall state.
        super.openSocketWith(session)

        if (isCancelled) { // isCancelled is from AdapterSocket base (_cancelled)
            println("INFO: SpeedAdapter: openSocketWith called on a cancelled adapter for session: $session")
            return
        }

        // Workaround for IPv6 from Swift code
        if (session.isIPv6()) {
            println("WARN: SpeedAdapter: IPv6 not supported by this SpeedAdapter setup (as per original Swift workaround). Disconnecting.")
            // AdapterSocket.disconnect will set _cancelled = true, update session, signal observer, call rawSocket.disconnect
            // Since rawSocket is null for SpeedAdapter, the rawSocket.disconnect part is a no-op.
            // It will then call this.didDisconnectWith (as delegate of itself if rawSocket was self, which is not the case)
            // or simply notify its own delegate.
            // We need to ensure that our own delegate is properly notified of failure.
            _status = SocketStatus.CLOSED // Set SpeedAdapter status
            _cancelled = true
            // Manually call didDisconnect on self's delegate as there's no underlying raw socket event for SpeedAdapter itself.
            this.delegate?.get()?.didDisconnect(this)
            return
        }

        if (subAdaptersConfig.isEmpty()) {
            System.err.println("ERROR: SpeedAdapter: No sub-adapters configured. Disconnecting.")
            _status = SocketStatus.CLOSED
            _cancelled = true
            this.delegate?.get()?.didErrorOccur(IllegalStateException("No sub-adapters configured for SpeedAdapter"), this)
            this.delegate?.get()?.didDisconnect(this)
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
                if (shouldConnect.get()) { // Check if we should still proceed
                    connectingCount.incrementAndGet()
                    pendingToConnectCount.decrementAndGet()
                    adapter.delegate = WeakReference(this@SpeedAdapter) // SpeedAdapter handles callbacks
                    try {
                        // Each sub-adapter will manage its own raw socket and connection process.
                        adapter.openSocketWith(session)
                    } catch (e: Exception) {
                        System.err.println("ERROR: SpeedAdapter: Exception calling openSocketWith on sub-adapter $adapter: ${e.message}")
                        // Treat as if this sub-adapter failed to connect
                        didDisconnect(adapter) // Signal failure for this specific sub-adapter
                    }
                } else {
                    pendingToConnectCount.decrementAndGet() // No longer pending, but not connecting either
                    // If shouldConnect became false, it means another adapter succeeded or SpeedAdapter was stopped.
                    // Check if this was the last pending one and if no one is connecting.
                    if (pendingToConnectCount.get() == 0 && connectingCount.get() == 0 && _status != SocketStatus.ESTABLISHED) {
                         println("INFO: SpeedAdapter: All sub-adapter attempts aborted or completed, none established overall connection.")
                         // This might happen if disconnect() was called rapidly after openSocketWith().
                         // Ensure final state is set if no adapter succeeded.
                         if (shouldConnect.get() == false && _status != SocketStatus.ESTABLISHED) { // check shouldConnect again for race
                            _status = SocketStatus.CLOSED
                            this@SpeedAdapter.delegate?.get()?.didDisconnect(this@SpeedAdapter)
                         }
                    }
                }
            }
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
        println("INFO: SpeedAdapter: disconnect called. Error: ${becauseOf?.message}")
        super.disconnect(becauseOf) // Sets _cancelled, _status=DISCONNECTING, notifies session, observer

        shouldConnect.set(false) // Stop any pending or new connection attempts from sub-adapters
        pendingToConnectCount.set(0) // No more pending connections

        val currentAdapters = ArrayList(subAdaptersConfig) // Iterate a copy
        for ((adapter, _) in currentAdapters) {
            adapter.delegate = null // Prevent further callbacks from sub-adapters
            if (adapter.status != SocketStatus.INVALID && adapter.status != SocketStatus.CLOSED) {
                adapter.disconnect(becauseOf)
            }
        }
        // The super.disconnect() already called this.rawSocket?.disconnect(), which is null for SpeedAdapter.
        // It did NOT call delegate.didDisconnect for SpeedAdapter itself yet.
        // That happens in RawTCPSocketDelegate.didDisconnect, which SpeedAdapter does not receive for its own null rawSocket.
        // So, if we are certain all sub-operations are stopping, we might need to signal our own delegate.
        // However, the logic in didDisconnectWith(socket: SocketProtocol) from sub-adapters handles the final failure state.
        // If all sub-adapters fail, that method will eventually call delegate.didDisconnect for SpeedAdapter.
        // If called externally before any sub-adapter connects, this ensures cleanup.
        if (connectingCount.get() == 0 && _status != SocketStatus.ESTABLISHED) {
             _status = SocketStatus.CLOSED
             this.delegate?.get()?.didDisconnect(this)
        }
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_cancelled && _status == SocketStatus.CLOSED) return
        println("INFO: SpeedAdapter: forceDisconnect called. Error: ${becauseOf?.message}")
        super.forceDisconnect(becauseOf) // Sets _cancelled, _status=DISCONNECTING, notifies session, observer

        shouldConnect.set(false)
        pendingToConnectCount.set(0)

        val currentAdapters = ArrayList(subAdaptersConfig)
        for ((adapter, _) in currentAdapters) {
            adapter.delegate = null
            if (adapter.status != SocketStatus.INVALID && adapter.status != SocketStatus.CLOSED) {
                adapter.forceDisconnect(becauseOf)
            }
        }
        if (connectingCount.get() == 0 && _status != SocketStatus.ESTABLISHED) {
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
        println("INFO: SpeedAdapter: Sub-adapter $adapterSocket reported raw connection (didConnect). Waiting for readyToForward.")
    }

    override fun didBecomeReadyToForward(socket: SocketProtocol) {
        val successfulAdapter = socket as? AdapterSocket ?: return

        if (!shouldConnect.getAndSet(false)) {
            // Another adapter already succeeded and was chosen, or SpeedAdapter was stopped.
            // Disconnect this late-coming adapter.
            println("INFO: SpeedAdapter: Another sub-adapter already chosen or process stopped. Disconnecting late $successfulAdapter.")
            successfulAdapter.delegate = null // Avoid further callbacks
            successfulAdapter.forceDisconnect()
            return
        }

        println("INFO: SpeedAdapter: Sub-adapter $successfulAdapter is ready to forward. Choosing this one.")
        _status = SocketStatus.ESTABLISHED // SpeedAdapter itself is now considered established.
        connectingCount.decrementAndGet() // This one is no longer "connecting" but "connected"
        pendingToConnectCount.set(0) // Stop any other pending connection initiations

        // Disconnect all other adapters that might still be connecting or pending.
        val currentAdapters = ArrayList(subAdaptersConfig)
        for ((adapter, _) in currentAdapters) {
            if (adapter !== successfulAdapter) {
                adapter.delegate = null // Remove delegate to prevent further callbacks
                if (adapter.status != SocketStatus.INVALID && adapter.status != SocketStatus.CLOSED) {
                    println("INFO: SpeedAdapter: Disconnecting other sub-adapter $adapter.")
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
        println("INFO: SpeedAdapter: Handed off to $successfulAdapter. SpeedAdapter is now inactive for this session.")
    }

    override fun didDisconnect(socket: SocketProtocol) {
        val adapterSocket = socket as? AdapterSocket ?: return
        println("INFO: SpeedAdapter: Sub-adapter $adapterSocket disconnected.")
        adapterSocket.delegate = null // Stop listening to this adapter

        val stillConnecting = connectingCount.decrementAndGet()
        val stillPending = pendingToConnectCount.get()

        if (shouldConnect.get()) { // If we haven't chosen an adapter yet
            if (stillConnecting == 0 && stillPending == 0) {
                // All attempts finished, and none succeeded in calling didBecomeReadyToForward first.
                println("ERROR: SpeedAdapter: All sub-adapters failed to connect or become ready.")
                _status = SocketStatus.CLOSED
                _cancelled = true // Mark self as cancelled as all options exhausted
                val mainDelegate = delegate?.get()
                delegate = null // Clear delegate
                mainDelegate?.didErrorOccur(IOException("All sub-adapters failed for SpeedAdapter"), this)
                mainDelegate?.didDisconnect(this)
            }
        } else {
            // A successful adapter was already chosen and `shouldConnect` is false, or SpeedAdapter was stopped.
            // This is just cleanup for other adapters that were racing.
            println("INFO: SpeedAdapter: Disconnect from sub-adapter $adapterSocket after one was already chosen or stopped.")
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
        println("INFO: SpeedAdapter: didRead called from sub-adapter $from. Data size: ${data.size}. (This should ideally not happen after hand-off).")
        delegate?.get()?.didRead(data, this) // Forwarding as SpeedAdapter if still active
    }

    override fun didWrite(data: ByteArray?, by: SocketProtocol) {
        println("INFO: SpeedAdapter: didWrite called from sub-adapter $by. (This should ideally not happen after hand-off).")
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
