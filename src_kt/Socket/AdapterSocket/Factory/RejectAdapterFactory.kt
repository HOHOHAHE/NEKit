import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import org.slf4j.LoggerFactory // Added import

// Assuming AdapterFactory.kt, ConnectSession.kt (Messages), AdapterSocket.kt, Opt.kt are available.
// Placeholder for RejectAdapter.kt needs to be defined or available.

// --- Placeholder for RejectAdapter ---
// TODO: Move to its own file: src_kt/Socket/AdapterSocket/RejectAdapter.kt
open class RejectAdapter(val delayMs: Int) : AdapterSocket(null /* No real raw socket for RejectAdapter */, observe = true) {
    private val logger = LoggerFactory.getLogger(RejectAdapter::class.java) // Logger for placeholder

    init {
        logger.info("Instance created with delay: {}ms.", delayMs)
        // A RejectAdapter doesn't truly connect, so its status might immediately be considered
        // disconnecting or closed after a delay. The openSocketWith will handle this.
    }

    override fun openSocketWith(session: ConnectSession) {
        // Call super to initialize session property, observer, etc.
        // Even though rawSocket is null, AdapterSocket.openSocketWith will handle it if it checks.
        // My current AdapterSocket.openSocketWith expects rawSocket to be set by subclass usually.
        // For RejectAdapter, rawSocket is not used for actual connection.
        // Let's ensure _status is managed.
        super.openSocketWith(session) // Sets this.session

        _status = SocketStatus.CONNECTING // Indicate it's "processing" the request

        logger.info("Simulating rejection for session {} after {}ms delay.", session, delayMs)

        val rejectScope = CoroutineScope(Dispatchers.Default) // TODO: Use a managed scope
        rejectScope.launch {
            delay(delayMs.toLong())

            // Ensure session is initialized before using it in error message
            val errorMessage = if (::_session.isInitialized) {
                "Connection rejected by RejectAdapter for ${this@RejectAdapter.session.host}:${this@RejectAdapter.session.port}"
            } else {
                "Connection rejected by RejectAdapter (session details unavailable)"
            }
            val error = IOException(errorMessage)

            // Update status and notify delegate
            // These delegate calls should ideally be dispatched on a consistent dispatcher
            // (e.g., the one provided to RawTCPSocketDelegate callbacks in other socket types).
            // For now, using the rejectScope's dispatcher.

            // From AdapterSocket's disconnect/forceDisconnect:
            this@RejectAdapter._status = SocketStatus.DISCONNECTING // Mark as disconnecting first
            this@RejectAdapter._cancelled = true // Internal cancel flag
            if (this@RejectAdapter::_session.isInitialized) {
                 this@RejectAdapter.session.disconnected(becauseOf = error, by = EventSource.ADAPTER)
            }
            this@RejectAdapter.observer?.signal(AdapterSocketEvent.ForceDisconnectCalled(this@RejectAdapter))
            // Since there's no real rawSocket to call disconnect on, directly call our own delegate's didDisconnect.

            this@RejectAdapter._status = SocketStatus.CLOSED
            this@RejectAdapter.observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this@RejectAdapter))
            this@RejectAdapter.observer?.signal(AdapterSocketEvent.Disconnected(this@RejectAdapter))

            val currentDelegate = this@RejectAdapter.delegate?.get()
            this@RejectAdapter.delegate = null // Clear delegate
            currentDelegate?.didErrorOccur(error, this@RejectAdapter) // Notify of error first
            currentDelegate?.didDisconnect(this@RejectAdapter) // Then notify of disconnect
        }
    }

    override fun write(data: ByteArray) { // Changed to non-suspend to match SocketProtocol
        throw IOException("Cannot write to RejectAdapter; connection is rejected.")
    }

    override fun readData() {
        // No data will ever be read; the connection is rejected.
        // Could immediately signal disconnect if a read is attempted on a "pending rejection"
        // or simply do nothing. If status is already CLOSED, delegate is null.
        if (status != SocketStatus.CLOSED) {
             logger.warn("readData() called for session {}, but connection is intended for rejection.", if(::_session.isInitialized) session else "uninitialized")
             // forceDisconnect(IOException("Read attempt on rejecting socket")) // Option: aggressively close
        }
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "RejectAdapter"} delay:${delayMs}ms session:$sessionStr>"
    }
}
// --- End Placeholder for RejectAdapter ---


/**
 * Factory for creating [RejectAdapter] instances.
 * A [RejectAdapter] simulates a connection rejection after a specified delay.
 *
 * @property delay The delay in milliseconds before the connection is "rejected".
 */
open class RejectAdapterFactory(
    val delay: Int = Opt.REJECT_ADAPTER_DEFAULT_DELAY // From Opt.kt
) : AdapterFactory() {

    /**
     * Creates and returns a [RejectAdapter] configured with the factory's delay.
     *
     * @param session The connect session for which the adapter is being created.
     * @return A new [RejectAdapter] instance.
     */
    override fun getAdapterFor(session: ConnectSession): AdapterSocket {
        return RejectAdapter(delay)
    }
}
