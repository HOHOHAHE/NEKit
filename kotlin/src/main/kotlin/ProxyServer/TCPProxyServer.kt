package nekit.ProxyServer
import kotlinx.coroutines.*
// import kotlinx.coroutines.sync.Mutex // No longer needed if Netty handles thread safety for start/stop
// import kotlinx.coroutines.sync.withLock // No longer needed

import org.slf4j.LoggerFactory
// Removed Java NIO imports as Netty will be used
// import java.nio.ByteBuffer
// import java.io.Closeable
// import java.net.InetSocketAddress
// import java.nio.channels.ServerSocketChannel
// import java.nio.channels.SocketChannel
// import java.nio.channels.Selector
// import java.nio.channels.SelectionKey


// Ktor imports
import io.ktor.network.selector.*
import io.ktor.network.sockets.*


// Assuming ProxyServer.kt, IPAddress.kt, Port.kt, QueueFactory.kt (placeholders) are available.
// Assuming RawTCPSocketProtocol.kt (from RawSocket module) is available for NettyAcceptedRawSocketAdapter.

import nekit.RawSocket.protocol.RawTCPSocketProtocol
import nekit.RawSocket.protocol.RawTCPSocketDelegate
import nekit.RawSocket.ktor.AcceptedRawTCPSocket
import nekit.RawSocket.core.NetworkDispatchers
import nekit.Socket.ProxySocket.ProxySocketInterface
import nekit.Utils.IPAddress
import nekit.Utils.Port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

abstract class TCPProxyServer(address: IPAddress?, port: Port) : ProxyServer(address, port) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private var serverSocket: ServerSocket? = null
    private var selectorManager: SelectorManager? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Throws(Exception::class)
    override suspend fun start() {
        if (serverSocket != null || selectorManager != null || serverJob != null) {
            logger.warn("Server already started or starting.")
            return
        }

        logger.info("Attempting to start Ktor server...")
        
        try {
            // 使用共享的 SelectorManager 而不是建立新的
            selectorManager = NetworkDispatchers.selectorManager
            
            val bindAddress = address?.presentation ?: "0.0.0.0"
            val bindPort = port.hostOrderValue.toInt()
            
            logger.info("Binding Ktor server to {}:{}", bindAddress, bindPort)
            
            serverSocket = aSocket(selectorManager!!).tcp().bind(bindAddress, bindPort)
            
            super.start() // Call ProxyServer's start for its logic (e.g., observer signals)
            
            // Start accepting connections in a coroutine
            serverJob = serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        logger.info("Ktor accepted new client connection: {}", clientSocket)
                        
                        // Handle each client connection in a separate coroutine
                        launch {
                            handleKtorClientConnection(clientSocket)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        break
                    } catch (e: Exception) {
                        logger.error("Accept error, retrying in 500ms: {}", e.message, e)
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            
            logger.info("Ktor server successfully started and listening on {}:{}", bindAddress, bindPort)
        } catch (e: Exception) {
            logger.error("Ktor server failed to start: {}", e.message, e)
            selectorManager?.close()
            selectorManager = null
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            throw e // Re-throw to indicate failure
        }
    }

    override suspend fun stop() {
        logger.info("Attempting to stop Ktor server...")

        // Cancel the server job first to stop accepting new connections
        serverJob?.cancel()
        serverJob?.join() // Wait for the job to complete
        serverJob = null
        logger.info("Ktor server job cancelled.")

        // Close the server socket
        serverSocket?.close()
        serverSocket = null
        logger.info("Ktor server socket closed.")

        // 不要關閉共享的 selector manager，只是清除引用
        selectorManager = null
        logger.info("Ktor selector manager reference cleared.")

        super.stop() // Call ProxyServer's stop for its logic
        serverScope.cancel()
        logger.info("Ktor server stopped.")
    }

    private suspend fun handleKtorClientConnection(clientSocket: Socket) {
        logger.info("Ktor handling new client connection: {}", clientSocket)
        val acceptedRawSocket = AcceptedRawTCPSocket(clientSocket)

        // The `handleNewAcceptedSocket` method is overridden by subclasses (GCDHTTPProxyServer, GCDSOCKS5ProxyServer)
        // to create their specific ProxySocket types (HTTPProxySocket, SOCKS5ProxySocket).
        // Those ProxySocket types now need to accept a RawTCPSocketProtocol (which AcceptedRawTCPSocket is).
        // This call dispatches to the appropriate overridden version.
        // With Ktor, we have native coroutine support, so suspend functions work naturally.
        // ProxyServer.didAcceptNewSocket (called by handleNewAcceptedSocket's overrides)
        // is a suspend function and uses a Mutex, which works well with Ktor's coroutine model.
        handleNewAcceptedSocket(acceptedRawSocket)
    }

    /**
     * Handles a newly accepted socket from the listening socket.
     * Subclasses (e.g., HTTPProxyServer, SOCKS5ProxyServer) must override this method
     * to provide specific proxy logic for the accepted connection.
     *
     * @param acceptedRawSocket The newly accepted socket, adapted to RawTCPSocketProtocol.
     */
    protected open fun handleNewAcceptedSocket(acceptedRawSocket: RawTCPSocketProtocol) {
        // Base implementation (if called directly, which shouldn't happen if subclasses override)
        logger.warn("TCPProxyServer.handleNewAcceptedSocket (base) called with {}. This should be overridden. Closing socket.", acceptedRawSocket)
        // This implies that if a subclass doesn't override, the raw socket might not be properly closed
        // as RawTCPSocketProtocol doesn't have a simple close(). It has disconnect/forceDisconnect.
        // For now, let's assume subclasses *will* override and handle the socket.
        // If direct close is needed: (acceptedRawSocket as? Closeable)?.close()
        // Or better:
        acceptedRawSocket.forceDisconnect(IOException("Base handleNewAcceptedSocket called, unhandled connection."))
    }

    // Removed didAcceptNewSocket(acceptedSocket: KotlinAcceptedSocketInterface) as KotlinServerSocketDelegate is no longer implemented.
    // Netty's ChannelInitializer handles new connections.
}
