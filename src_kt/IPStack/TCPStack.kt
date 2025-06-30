package com.example.nekit.IPStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import org.slf4j.LoggerFactory

import com.example.nekit.IPStack.Native.JnaLibTun2Socks
import com.example.nekit.IPStack.Native.LibTun2SocksStackCallbacks
import com.example.nekit.IPStack.Native.LibTun2SocksSocketCallbacks
import com.example.nekit.IPStack.Native.LibTun2SocksStackInterface

import com.example.nekit.RawSocket.TUNTCPSocket
import com.example.nekit.ProxyServer.ProxyServer
import com.example.nekit.Socket.ProxySocket.DirectProxySocket
import com.example.nekit.Tunnel.QueueFactory
import com.example.nekit.IPStack.Packet.IPPacket
import com.example.nekit.IPStack.IPStackProtocol
import com.example.nekit.IPStack.AddressFamily



/**
 * Kotlin wrapper for the tun2socks TCP/IP stack, now using JnaLibTun2Socks.
 * Implements IPStackProtocol to integrate with a TUN interface.
 */
object TCPStack : LibTun2SocksStackCallbacks, IPStackProtocol {
    private val logger = LoggerFactory.getLogger(TCPStack::class.java)
    private val tun2socksStack: LibTun2SocksStackInterface = JnaLibTun2Socks()
    private val activeSockets = mutableMapOf<Int, TUNTCPSocket>()
    private val activeSocketsMutex = Mutex() // To protect activeSockets map

    // Using WeakReference for proxyServer to avoid potential retain cycles.
    private var _proxyServerRef: WeakReference<ProxyServer?> = WeakReference(null)
    var proxyServer: ProxyServer?
        get() = _proxyServerRef.get()
        set(value) {
            _proxyServerRef = WeakReference(value)
            logger.info("ProxyServer was set (is null: {}).", value == null)
        }

    // This is called by TUNInterface when it has IP packets for this stack.
    override var outputFunc: ((packets: List<ByteArray>, versions: List<AddressFamily>) -> Unit)? = null


    init {
        logger.info("TCPStack (JNA-based) initialized.")
        // Initialization of tun2socksStack (init_stack) is deferred to start()
        // as it requires passing 'this' as callbacks.
    }

    override fun input(packet: ByteArray, version: Int?): Boolean {
        // Assuming tun2socks handles IPv4/IPv6 internally or inputPacket doesn't need version hint.
        // The version from TUNInterface is based on a simple peek.
        // tun2socks will parse the IP header more thoroughly.
        if (IPPacket.peekProtocol(packet) == TransportProtocol.TCP) {
            logger.trace("TCPStack input: Passing TCP packet ({} bytes) to tun2socksStack.", packet.size)
            tun2socksStack.inputPacket(packet, packet.size)
            return true
        }
        logger.trace("TCPStack input: Packet ({} bytes) is not TCP, ignoring.", packet.size)
        return false
    }

    override fun start() {
        logger.info("TCPStack start() called. Initializing and starting JnaLibTun2Socks...")
        try {
            tun2socksStack.init(this as LibTun2SocksStackCallbacks)
            if (tun2socksStack.start()) {
                logger.info("JnaLibTun2Socks started successfully.")
            } else {
                logger.error("JnaLibTun2Socks failed to start.")
                // Handle start failure, e.g., by stopping associated components or throwing.
            }
        } catch (e: Exception) {
            logger.error("Exception during TCPStack start: {}", e.message, e)
            // Handle exceptions during init or start
        }
    }

    override fun stop() {
        logger.info("TCPStack stop() called. Stopping JnaLibTun2Socks and cleaning up active sockets...")
        tun2socksStack.stop()

        // Close all active TUNTCPSockets managed by this stack
        // Use runBlocking if these closeTcp calls are suspending and stop() is not.
        // Or make stop() suspend. For now, assuming closeTcp is non-blocking JNA call.
        runBlocking { // Or use a dedicated scope for cleanup.
            activeSocketsMutex.withLock {
                activeSockets.values.forEach { socket ->
                    try {
                        socket.forceDisconnect() // Or a more graceful disconnect if appropriate
                    } catch (e: Exception) {
                        logger.warn("Exception closing active TUNTCPSocket (id: {}): {}", (socket as? TUNTCPSocket)?.socketId ?: "N/A", e.message)
                    }
                }
                activeSockets.clear()
            }
        }
        _proxyServerRef = WeakReference(null) // Clear proxy server reference
        logger.info("TCPStack stopped.")
    }

    // --- LibTun2SocksStackCallbacks Implementation ---

    override fun writePacket(protocol: Int, packet: ByteArray, len: Int): Int {
        logger.trace("tun2socks wants to write packet (proto:{}, len:{}) to TUN.", protocol, len)
        val actualPacket = if (len < packet.size) packet.copyOfRange(0, len) else packet
        // Assuming outputFunc expects a List<ByteArray> and List<Int> (versions)
        // The protocol here might be an IP version or transport protocol.
        // For TUN output, it's typically IP version (AddressFamily.AF_INET/AF_INET6).
        // For now, assume 'protocol' can be used as 'version'.
        outputFunc?.invoke(listOf(actualPacket), listOf(AddressFamily.fromInt(protocol)))
        return actualPacket.size // Return number of bytes "written" (passed to outputFunc)
    }

    override fun onTcpSocketCreated(socketId: Int, context: Any?): LibTun2SocksSocketCallbacks? {
        logger.info("tun2socks created new TCP socket with id: {}", socketId)
        // This coroutine scope should be tied to TCPStack's lifecycle or a global one for socket operations.
        // Using GlobalScope here is not ideal for production but works for now.
        // Better: Use a scope that can be cancelled when TCPStack stops.
        val tunnelScope = CoroutineScope(Dispatchers.Default + SupervisorJob()) // Placeholder scope

        val newTunSocket = TUNTCPSocket(socketId, tun2socksStack, true, tunnelScope)

        runBlocking { // Use runBlocking if activeSocketsMutex.withLock is not suspend and map operations are quick
            activeSocketsMutex.withLock {
                activeSockets[socketId] = newTunSocket
            }
        }

        tunnelScope.launch { // Launch a coroutine to call suspend function
            proxyServer?.didAcceptNewSocket(DirectProxySocket(newTunSocket))
                ?: logger.warn("No proxyServer delegate set in TCPStack to handle new TCP socketId: {}", socketId)
        }

        return newTunSocket // TUNTCPSocket implements LibTun2SocksSocketCallbacks
    }

    override fun onUdpSocketCreated(socketId: Int, context: Any?): LibTun2SocksSocketCallbacks? {
        logger.warn("onUdpSocketCreated (socketId: {}) - UDP not yet fully supported via JnaLibTun2Socks in TCPStack.", socketId)
        // TODO: Implement UDP handling if tun2socks supports it and it's needed.
        // This might involve creating a TUNUDPSocket and similar logic to TCP.
        return null
    }
}
