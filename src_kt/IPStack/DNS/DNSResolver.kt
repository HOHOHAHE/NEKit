package nekit.IPStack.DNS
import java.lang.ref.WeakReference


import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.RawSocket.ktor.RawUDPSocket
import nekit.RawSocket.protocol.RawUDPSocketProtocol
import nekit.RawSocket.protocol.RawUDPSocketDelegate
import nekit.IPStack.DNS.DNSSession // Replaced placeholder with actual import


import org.slf4j.LoggerFactory

// --- Removed Placeholders for KotlinUDPSocket ---
// KotlinUDPSocketDelegate, KotlinUDPSocket, PlaceholderUDPSocket


interface DNSResolverProtocol {
    var delegate: DNSResolverDelegate?
    fun resolve(session: DNSSession)
    fun stop()
}

interface DNSResolverDelegate {
    fun didReceive(rawResponse: ByteArray)
}

open class UDPDNSResolver(
    private val remoteAddress: IPAddress, // Store remote DNS server address
    private val remotePort: Port           // Store remote DNS server port
) : DNSResolverProtocol, RawUDPSocketDelegate {

    private val logger = LoggerFactory.getLogger(UDPDNSResolver::class.java)
    // Instantiate RawUDPSocket with host and port
    private val socket: RawUDPSocketProtocol = RawUDPSocket(remoteAddress.presentation, remotePort.hostOrderValue)

    override var delegate: DNSResolverDelegate? = null

    init {
        socket.delegate = WeakReference(this) // Set this resolver as the delegate for socket events
        try {
            // Bind to an ephemeral port on all local interfaces.
            // DNS client usually doesn't need a fixed local port.
            socket.bind(host = null, port = 0)
            logger.info("UDPDNSResolver bound to local address: {}", socket.localAddress)
        } catch (e: Exception) {
            logger.error("Failed to bind UDPDNSResolver socket: {}", e.message, e)
            // This resolver might be unusable if bind fails.
            // Consider throwing an exception or having a resolvable state.
        }
    }

    override fun resolve(session: DNSSession) {
        val payload = session.builtRequestPayload
        if (payload != null) {
            try {
                logger.debug("Sending DNS query for {} ({} bytes) to {}:{}",
                    session.requestMessage.queries.firstOrNull()?.name ?: "N/A",
                    payload.size,
                    remoteAddress.presentation,
                    remotePort.hostOrderValue)
                socket.send(
                    data = payload,
                    destinationHost = remoteAddress.presentation,
                    destinationPort = remotePort.hostOrderValue
                )
            } catch (e: Exception) {
                logger.error("Failed to send DNS query for session {}: {}", session, e.message, e)
                // Optionally, notify DNSResolverDelegate of the error immediately
                // delegate?.didFailToResolve(session, e)
            }
        } else {
            logger.error("DNS request payload is null for session resolving {}", session.requestMessage.queries.firstOrNull()?.name)
        }
    }

    override fun stop() {
        logger.info("Stopping UDPDNSResolver and disconnecting socket.")
        socket.disconnect()
    }

    // Implementation of RawUDPSocketDelegate
    override fun didReceive(data: ByteArray, from: RawUDPSocketProtocol) {
        // Check if the response is from the expected DNS server
        // fromHost and fromPort are properties of the 'from' socket, not global
        val fromHost = from.sourceIPAddress?.presentation ?: "unknown"
        val fromPort = from.sourcePort?.hostOrderValue ?: -1
        if (fromHost == remoteAddress.presentation && fromPort == remotePort.hostOrderValue) {
            logger.debug("Received {} bytes DNS response from {}:{}", data.size, fromHost, fromPort)
            delegate?.didReceive(rawResponse = data)
        } else {
            logger.warn("Received UDP packet from unexpected source {}:{}. Expected {}:{}. Ignoring.",
                fromHost, fromPort, remoteAddress.presentation, remotePort.hostOrderValue)
        }
    }

    override fun didErrorOccur(error: Throwable, onSocket: RawUDPSocketProtocol) {
        logger.error("Error occurred on UDP socket for DNS resolver: {}", error.message, error)
        // This might indicate a problem with the socket that could affect future resolutions.
        // Depending on the error, might need to re-initialize the socket or signal failure for pending queries.
        // For now, just logging. If it's a fatal socket error, new sends might fail.
    }

    override fun didCancel(socket: RawUDPSocketProtocol) {
        logger.info("UDPDNSResolver socket cancelled: {}", socket)
        // Handle cancellation, potentially clean up resources
    }
}
