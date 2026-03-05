package com.example.nekit.ProxyServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope // For launching super.didAcceptNewSocket
import kotlinx.coroutines.launch     // For launching super.didAcceptNewSocket
import org.slf4j.LoggerFactory

// Assuming TCPProxyServer.kt, IPAddress.kt, Port.kt are available.
// Assuming KotlinAcceptedSocketInterface, ProxySocketInterface are available from TCPProxyServer.kt context or common files.

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Socket.ProxySocket.SOCKS5ProxySocket
import com.example.nekit.RawSocket.protocol.RawTCPSocketProtocol
import com.example.nekit.Config.NetworkInterfaceType


/**
 * The SOCKS5 proxy server.
 * Extends TCPProxyServer to handle incoming TCP connections as SOCKS5 proxy sessions.
 */
class SOCKS5ProxyServer : TCPProxyServer {
    // Inherits logger from ProxyServer, or can define its own if specific logging needed here.
    private val socks5Logger = LoggerFactory.getLogger(SOCKS5ProxyServer::class.java)

    /**
     * Creates an instance of SOCKS5 proxy server.
     *
     * @param address The IP address for the server to listen on. Can be null to listen on all interfaces.
     * @param port The port for the server to listen on.
     * @param mainDispatcher Optional CoroutineDispatcher for handling delegate callbacks, defaults to Dispatchers.Default.
     */
    constructor(
        address: IPAddress?,
        port: Port
    ) : super(address, port)

    var outboundInterfaceType: NetworkInterfaceType = NetworkInterfaceType.DEFAULT

    /**
     * Handles a newly accepted socket from the listening server socket by wrapping it
     * into a SOCKS5ProxySocket and passing it to the base class's tunnel management logic.
     *
     * @param acceptedSocket The newly accepted socket (e.g., KotlinTCPSocketWrapper).
     */
    override fun handleNewAcceptedSocket(socket: RawTCPSocketProtocol) {
        socks5Logger.info("New socket accepted, wrapping as SOCKS5ProxySocket: {}", socket)
        val socks5ProxySocket = SOCKS5ProxySocket(socket)
        socks5ProxySocket.outboundInterfaceType = this.outboundInterfaceType
        
        // Launch the call to super.didAcceptNewSocket in a coroutine scope
        // as didAcceptNewSocket in ProxyServer is a suspend function using a Mutex.
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                super.didAcceptNewSocket(socks5ProxySocket)
            } catch (e: Exception) {
                socks5Logger.error("Error processing newly accepted SOCKS5 socket {}: {}", socket, e.message, e)
                try {
                    socket.forceDisconnect(e) // Close the raw socket if super.didAcceptNewSocket fails
                } catch (ioe: Exception) {
                    socks5Logger.error("Exception closing socket {} after error: {}", socket, ioe.message, ioe)
                }
            }
        }
    }
}
