package Socket.AdapterSocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.io.IOException // For connection exceptions

import Messages.ConnectSession
import RawSocket.RawTCPSocketProtocol
import RawSocket.RawSocketFactory
import Socket.SocketStatus

/**
 * Adapter for making a direct connection to a remote host.
 * It uses a [RawTCPSocketProtocol] to establish and manage the connection.
 */
open class DirectAdapter(
    initialRawSocket: RawTCPSocketProtocol = RawSocketFactory.getRawSocket()
) : AdapterSocket(initialRawSocket) {

    private val directAdapterLogger = LoggerFactory.getLogger(DirectAdapter::class.java)

    // Managed CoroutineScope for the DirectAdapter lifecycle
    private val directAdapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        directAdapterLogger.info("DirectAdapter created with rawSocket: {}", rawSocket)
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session) // Sets up session, registers delegate to rawSocket

        val currentRawSocket = rawSocket ?: run {
            directAdapterLogger.error("Raw socket is null in openSocketWith for session: {}. This should not happen if constructor provides it.", session)
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didDisconnect(this)
            return
        }

        if (isCancelled) {
            directAdapterLogger.info("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        _status = SocketStatus.CONNECTING // Set status before attempting connection
        directAdapterLogger.info("Attempting direct connection for session: {} to host {}:{}", session, session.host, session.port)

        directAdapterScope.launch {
            try {
                currentRawSocket.connectTo(session.host, session.port)
                // Connection result will be handled by didConnect/didDisconnect callbacks (RawTCPSocketDelegate)
                // which in turn update AdapterSocket status and call SocketDelegate.
            } catch (e: Exception) {
                directAdapterLogger.error("Failed to connect to {}:{}: {}", session.host, session.port, e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        directAdapterLogger.error("Direct connection failure: {}", error.message, error)
        // Use AdapterSocket's forceDisconnect to ensure proper state update and delegate notification
        forceDisconnect(becauseOf = error)
    }

    override fun disconnect(becauseOf: Throwable?) {
        directAdapterLogger.info("disconnect called for session {}. Error: {}", session, becauseOf?.message)
        directAdapterScope.cancel("DirectAdapter disconnected") // Cancel all coroutines in this scope
        super.disconnect(becauseOf)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        directAdapterLogger.info("forceDisconnect called for session {}. Error: {}", session, becauseOf?.message)
        directAdapterScope.cancel("DirectAdapter force-disconnected") // Cancel all coroutines in this scope
        super.forceDisconnect(becauseOf)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "DirectAdapter"} session:$sessionStr status:$status>"
    }
}
