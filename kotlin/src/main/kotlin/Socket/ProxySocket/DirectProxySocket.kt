package nekit.Socket.ProxySocket

import nekit.Messages.ConnectSession
import nekit.Socket.SocketProtocol
import nekit.Event.Event.ProxySocketEvent
import org.slf4j.LoggerFactory

class DirectProxySocket(
    socket: SocketProtocol
) : ProxySocket(socket.rawSocket!!) {

    private val logger = LoggerFactory.getLogger(DirectProxySocket::class.java)

    override fun openSocket() {
        super.openSocket()
        // For a direct proxy, we can immediately consider the session established
        // and notify the delegate.
        val session = ConnectSession(
            host = destinationIPAddress?.toString() ?: "",
            port = destinationPort?.hostOrderValue ?: 0
        )
        this.session = session
        
        logger.info("Direct proxy socket opened for session: $session")
        observer?.signal(ProxySocketEvent.ReceivedRequest(this))
        delegate?.get()?.didReceive(session, this)
    }
}
