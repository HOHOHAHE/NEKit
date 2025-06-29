package com.example.nekit.ProxyServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // Added missing import
import org.slf4j.LoggerFactory

// Assuming GCDProxyServer.kt, IPAddress.kt, Port.kt are available.
// Assuming KotlinAcceptedSocketInterface, ProxySocketInterface are available from GCDProxyServer.kt context or common files.

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Socket.ProxySocket.HTTPProxySocket
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.ProxySocket


/**
 * The HTTP proxy server.
 * Extends GCDProxyServer to handle incoming TCP connections as HTTP proxy sessions.
 */
class GCDHTTPProxyServer : GCDProxyServer {
    // Inherits logger from ProxyServer, or can define its own if specific logging needed here.
    // For instance-specific logging related to GCDHTTPProxyServer behavior, can add:
    private val httpLogger = LoggerFactory.getLogger(GCDHTTPProxyServer::class.java)


    /**
     * Creates an instance of HTTP proxy server.
     *
     * @param address The IP address for the server to listen on. Can be null to listen on all interfaces.
     * @param port The port for the server to listen on.
     * @param mainDispatcher Optional CoroutineDispatcher for handling delegate callbacks, defaults to Dispatchers.Default.
     */
    constructor(
        address: IPAddress?,
        port: Port,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Default // Keep consistent with GCDProxyServer's constructor if it adds this
    ) : super(address, port, mainDispatcher)

    /**
     * Handles a newly accepted socket from the listening server socket by wrapping it
     * into an HTTPProxySocket and passing it to the base class's tunnel management logic.
     *
     * @param acceptedSocket The newly accepted socket (e.g., KotlinTCPSocketWrapper).
     */
    override fun handleNewAcceptedSocket(acceptedSocket: KotlinAcceptedSocketInterface) {
        httpLogger.info("New socket accepted, wrapping as HTTPProxySocket: {}", acceptedSocket)
        val httpProxySocket = HTTPProxySocket(acceptedSocket)

        // Launch the call to super.didAcceptNewSocket in the server's main coroutine scope
        // as didAcceptNewSocket in ProxyServer is a suspend function using a Mutex.
        // This ensures that if handleNewAcceptedSocket is called from a different thread (e.g. NIO selector thread),
        // it correctly suspends and resumes on the dispatcher expected by ProxyServer's Mutex operations.
        // Using mainDispatcher passed to GCDProxyServer, or a default one.
        // Note: ProxyServer.didAcceptNewSocket itself launches Tunnel.openTunnel, which might be async.
        // This launch here is for the call to didAcceptNewSocket itself.
        val scope = CoroutineScope(mainDispatcher) // Or use a dedicated scope from GCDProxyServer if available
        scope.launch {
            try {
                super.didAcceptNewSocket(httpProxySocket)
            } catch (e: Exception) {
                httpLogger.error("Error processing newly accepted HTTP socket {}: {}", acceptedSocket, e.message, e)
                try {
                    acceptedSocket.close() // Close the raw socket if super.didAcceptNewSocket fails
                } catch (ioe: Exception) {
                    httpLogger.error("Exception closing socket {} after error: {}", acceptedSocket, ioe.message, ioe)
                }
            }
        }
    }
}
