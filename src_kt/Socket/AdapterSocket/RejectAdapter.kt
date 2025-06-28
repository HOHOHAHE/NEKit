import kotlinx.coroutines.*
import kotlinx.coroutines.cancel // Added for managed scope
import java.io.IOException // For creating an error object if needed
import org.slf4j.LoggerFactory

import Messages.ConnectSession // Corrected import
import Tunnel.QueueFactory // Corrected import
import Socket.SocketStatus // Corrected import
import Event.Event.AdapterSocketEvent // Corrected import
import Messages.EventSource // Corrected import

/**
 * Adapter that simulates a connection rejection after a specified delay.
 * It does not perform any actual network operations.
 *
 * @param delayMs The delay in milliseconds before the connection is "rejected".
 */
class RejectAdapter(
    val delayMs: Int
) : AdapterSocket(initialRawSocket = null, observe = true) { // Pass null for rawSocket as it's not used

    private val rejectAdapterLogger = LoggerFactory.getLogger(RejectAdapter::class.java)
    private var rejectionJob: Job? = null
    // Dedicated scope for this adapter's operations, like the delayed rejection.
    // TODO: This scope should be managed (e.g., cancelled in a cleanup method if RejectAdapter had one).
    private val adapterScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


    init {
        // Initial status is INVALID. It will transition briefly during openSocketWith.
        _status = SocketStatus.INVALID
        rejectAdapterLogger.info("Created with delay: {}ms.", delayMs)
    }

    override fun openSocketWith(session: ConnectSession) {
        // Set session information from AdapterSocket's perspective.
        // The super.openSocketWith also sets rawSocket.delegate = this, which is fine even if rawSocket is null.
        super.openSocketWith(session)

        if (isCancelled) { // Check if already cancelled
            _status = SocketStatus.CLOSED
            delegate?.get()?.didDisconnect(this)
            return
        }

        _status = SocketStatus.CONNECTING // Simulate attempting to connect
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session)) // Signal opening attempt

        rejectAdapterLogger.info("Simulating connection rejection for session {} after {}ms delay.", session, delayMs)

        rejectionJob = adapterScope.launch {
            delay(delayMs.toLong())

            // Ensure the socket wasn't cancelled while delaying
            if (isActive && !isCancelled) { // isActive checks coroutine scope, isCancelled is our flag
                rejectAdapterLogger.info("Delay elapsed, now rejecting session {}.", session)
                // Simulate a rejection error
                val rejectionError = IOException("Connection rejected by policy: ${session.host}:${session.port}")
                // Use the disconnect method which handles state and delegate notification.
                // Passing the error to disconnect.
                disconnect(becauseOf = rejectionError)
            } else if (isCancelled) {
                 rejectAdapterLogger.info("Rejection for session {} was cancelled during delay.", session)
                 // If it was cancelled by forceDisconnect, the delegate notification might have already happened.
                 // If only `_cancelled` is true but status not CLOSED, ensure proper cleanup.
                 if (_status != SocketStatus.CLOSED) {
                     _status = SocketStatus.CLOSED
                     // If disconnect() or forceDisconnect() was called, they would have handled delegate.
                     // If only _cancelled was set somehow, this ensures cleanup.
                     delegate?.get()?.didDisconnect(this@RejectAdapter)
                 }
            }
        }
    }

    /**
     * Disconnects the socket (simulated).
     * This is called after the delay in `openSocketWith` or can be called externally.
     */
    override fun disconnect(becauseOf: Throwable?) {
        if (_status == SocketStatus.CLOSED && _cancelled) return // Already processed a full disconnect

        rejectionJob?.cancel() // Cancel any pending delayed rejection
        adapterScope.cancel("RejectAdapter disconnected") // Cancel the scope

        _status = SocketStatus.DISCONNECTING // Transition state
        _cancelled = true // Mark as cancelled by local action (rejection)

        if (::_session.isInitialized) { // Check if session was set
            session.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
        }
        observer?.signal(AdapterSocketEvent.DisconnectCalled(this)) // Standard disconnect event

        // If there was an error causing this disconnect (e.g. rejection), signal it
        if (becauseOf != null) {
            observer?.signal(AdapterSocketEvent.ErrorOccurred(becauseOf, this))
        }

        _status = SocketStatus.CLOSED
        val currentDelegate = delegate?.get()
        delegate = null // Clear delegate
        currentDelegate?.didDisconnect(this) // Notify delegate
        rejectAdapterLogger.info("Session {} disconnected/rejected. Error: {}", session, becauseOf?.message)
    }

    /**
     * Forces an immediate disconnect (simulated).
     */
    override fun forceDisconnect(becauseOf: Throwable?) {
        if (_status == SocketStatus.CLOSED && _cancelled) return

        rejectionJob?.cancel()
        adapterScope.cancel("RejectAdapter force-disconnected") // Cancel the scope

        _status = SocketStatus.DISCONNECTING
        _cancelled = true
        if (::_session.isInitialized) {
            session.disconnected(becauseOf = becauseOf, by = EventSource.ADAPTER)
        }
        observer?.signal(AdapterSocketEvent.ForceDisconnectCalled(this))

        if (becauseOf != null) {
            observer?.signal(AdapterSocketEvent.ErrorOccurred(becauseOf, this))
        }

        _status = SocketStatus.CLOSED
        val currentDelegate = delegate?.get()
        delegate = null
        currentDelegate?.didDisconnect(this)
        rejectAdapterLogger.info("Session {} force disconnected/rejected. Error: {}", session, becauseOf?.message)
    }

    // Override write and read to be no-ops or throw, as a RejectAdapter does not transfer data.
    override fun write(data: ByteArray) { // Changed to non-suspend to match SocketProtocol
        if (!isCancelled) { // Only throw if not already "closed" by rejection
            throw IOException("Cannot write to RejectAdapter; connection is rejected.")
        }
    }

    override fun readData() {
        if (!isCancelled) {
            // No data will ever be read. Could signal error or disconnect immediately if called.
            // For robustness, ensure it leads to a disconnected state if not already.
            // forceDisconnect(becauseOf = IOException("Read attempt on a rejecting socket."))
             rejectAdapterLogger.warn("readData() called, but this adapter rejects connections.")
        }
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "RejectAdapter"} delay:${delayMs}ms session:$sessionStr status:$status>"
    }
}
