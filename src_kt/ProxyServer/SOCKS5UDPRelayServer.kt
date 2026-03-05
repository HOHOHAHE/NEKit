package com.example.nekit.ProxyServer

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.KtorRawUDPSocket
import com.example.nekit.RawSocket.RawCellularUDPSocket
import com.example.nekit.RawSocket.RawUDPSocketProtocol
import com.example.nekit.Socket.ProxySocket.SOCKS5ProxySocket
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Config.NetworkInterfaceType
import com.example.nekit.Config.GlobalNetworkManager
import com.example.nekit.Utils.PlatformDetector
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Handles the UDP ASSOCIATE relay process for SOCKS5.
 * 
 * When a SOCKS5 client requests UDP ASSOCIATE, this server binds to an ephemeral UDP port
 * and relays datagrams between the client and the target server.
 */
class SOCKS5UDPRelayServer(
    private val expectedClientAddress: IPAddress,
    private val expectedClientPort: Port,
    private val socks5ProxySocket: SOCKS5ProxySocket,
    val outboundInterfaceType: NetworkInterfaceType = NetworkInterfaceType.DEFAULT
) {
    private val logger = LoggerFactory.getLogger(SOCKS5UDPRelayServer::class.java)
    
    // The socket listening for UDP datagrams from the local SOCKS5 client
    private var clientSocket: RawUDPSocketProtocol? = null
    
    // A single unified outbound socket to external targets (multiplexes all internet UDP traffic)
    // This prevents Dispatchers.IO exhaustion from spawning too many blocking DatagramSocket.receive() threads
    private var outboundSocket: RawUDPSocketProtocol? = null
    
    // Coroutine scope for handling UDP relay operations
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Will be populated once the UDP socket is successfully bound
    var boundAddress: IPAddress? = null
        private set
    var boundPort: Port? = null
        private set
        
    /**
     * Starts the UDP relay server by binding to an ephemeral port.
     * 
     * @return true if successful, false otherwise.
     */
    suspend fun start(): Boolean {
        try {
            // The CLIENT listening socket must ALWAYS be a standard socket (not cellular bound)
            // because SOCKS5 clients on the same device communicate via loopback (127.0.0.1).
            // Cellular interfaces cannot route 127.0.0.1.
            val socket = KtorRawUDPSocket("0.0.0.0", 0)
            
            // Set up our callback listener BEFORE binding so we don't miss packets
            socket.onDatagramReceived = { data, sourceAddress, sourcePort ->
                handleIncomingDatagram(data, sourceAddress, sourcePort)
            }
            
            // Wait for it to bind
            socket.suspendBind(null, 0)
            
            // Retrieve the bound port info
            val localAddr = socket.localAddress
            val localPort = socket.sourcePort
            
            if (localAddr != null && localPort != null) {
                boundAddress = localAddr
                boundPort = localPort
                clientSocket = socket
                logger.info("SOCKS5 UDP Relay started on {}:{} for client requests", boundAddress?.presentation, boundPort?.hostOrderValue)
                return true
            } else {
                logger.error("Failed to retrieve local bound address/port for UDP Relay")
                socket.disconnect()
                return false
            }
        } catch (e: Exception) {
            logger.error("Failed to start SOCKS5 UDP Relay: {}", e.message, e)
            return false
        }
    }
    
    /**
     * Stops the UDP relay and cleans up resources.
     */
    fun stop() {
        logger.info("Stopping SOCKS5 UDP Relay on {}:{}", boundAddress?.presentation, boundPort?.hostOrderValue)
        relayScope.cancel()
        clientSocket?.disconnect()
        clientSocket = null
        
        outboundSocket?.disconnect()
        outboundSocket = null
    }

    private var actualClientAddress: IPAddress? = null
    private var actualClientPort: Port? = null

    /**
     * Handles an incoming UDP datagram.
     * 
     * The datagram can either be:
     * 1. From the client (needs to be relayed to a target server)
     * 2. From a target server (needs to be relayed back to the client)
     */
    private fun handleIncomingDatagram(data: ByteArray, sourceAddress: IPAddress, sourcePort: Port) {
        relayScope.launch {
            try {
                // Determine if this datagram is coming from the connected SOCKS5 client
                // Note: The client might send from a different IP/Port than expected, 
                // but checking against the expected client's IP is good to learn the port.
                // If actualClientAddress is already learned, use it.
                val isFromClient = if (actualClientAddress != null && actualClientPort != null) {
                    sourceAddress.presentation == actualClientAddress!!.presentation && sourcePort.hostOrderValue == actualClientPort!!.hostOrderValue
                } else if (sourceAddress.presentation == expectedClientAddress.presentation || expectedClientAddress.presentation == "0.0.0.0" || expectedClientAddress.presentation == "::" ||
                           expectedClientAddress.presentation == "0:0:0:0:0:0:0:0" || expectedClientAddress.presentation == "127.0.0.1" && sourceAddress.presentation == "::1") {
                    // This is likely the first packet from the client. Update actual client info.
                    actualClientAddress = sourceAddress
                    actualClientPort = sourcePort
                    true
                } else {
                    false
                }
                
                if (isFromClient) {
                    handleDatagramFromClient(data)
                } else {
                    handleDatagramFromTarget(data, sourceAddress, sourcePort)
                }
            } catch (e: Exception) {
                logger.warn("Error processing UDP datagram on relay: {}", e.message)
            }
        }
    }
    
    /**
     * Processes a datagram received from the SOCKS5 client.
     * The payload contains a SOCKS5 UDP request header followed by the actual data.
     *
     * +----+------+------+----------+----------+----------+
     * |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
     * +----+------+------+----------+----------+----------+
     * | 2  |  1   |  1   | Variable |    2     | Variable |
     * +----+------+------+----------+----------+----------+
     */
    private suspend fun handleDatagramFromClient(data: ByteArray) {
        if (data.size < 10) { // Minimum size for RSV+FRAG+ATYP+IPv4+PORT
            logger.warn("Received UDP datagram from client is too small: {} bytes", data.size)
            return
        }
        
        val buffer = ByteBuffer.wrap(data)
        
        // RSV
        val rsv = buffer.short
        if (rsv.toInt() != 0) {
            logger.warn("Invalid RSV field in UDP datagram: {}", rsv)
            return
        }
        
        // FRAG
        val frag = buffer.get()
        if (frag.toInt() != 0) {
            // Fragmentation is not supported (and rarely used in practice)
            logger.warn("Dropping fragmented UDP datagram (FRAG={})", frag)
            return
        }
        
        // ATYP
        val atyp = buffer.get()
        var destHostStr: String
        
        when (atyp.toInt()) {
            0x01 -> { // IPv4
                if (buffer.remaining() < 4 + 2) return // IP + Port
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                destHostStr = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // Domain name
                if (buffer.remaining() < 1) return
                val domainLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < domainLen + 2) return // Domain + Port
                val domainBytes = ByteArray(domainLen)
                buffer.get(domainBytes)
                destHostStr = String(domainBytes)
            }
            0x04 -> { // IPv6
                if (buffer.remaining() < 16 + 2) return // IP + Port
                val ipBytes = ByteArray(16)
                buffer.get(ipBytes)
                destHostStr = ipBytes.asList().chunked(2).joinToString(":") {
                    String.format("%02x%02x", it[0], it[1])
                }
            }
            else -> {
                logger.warn("Unsupported address type in UDP datagram: {}", atyp)
                return
            }
        }
        
        // DST.PORT
        val destPortInt = buffer.short.toInt() and 0xFFFF
        
        // DATA
        val payloadData = ByteArray(buffer.remaining())
        buffer.get(payloadData)
        
        logger.info("Relaying UDP: Client -> Target ({}:{}) | {} bytes payload", destHostStr, destPortInt, payloadData.size)
        
        // Retrieve or create the unified outbound socket
        val targetSocket = getOrCreateOutboundSocket()
        
        // Forward the actual payload to the target server
        targetSocket?.send(payloadData, destHostStr, destPortInt)
    }

    /**
     * Creates and caches a single outbound UDP socket (e.g., Cellular) to talk to all external target servers.
     */
    private suspend fun getOrCreateOutboundSocket(): RawUDPSocketProtocol? {
        outboundSocket?.let { return it }

        val activeInterface = if (outboundInterfaceType != NetworkInterfaceType.DEFAULT) {
            outboundInterfaceType
        } else {
            GlobalNetworkManager.currentActiveInterface
        }

        val socket = if (PlatformDetector.isAndroid && activeInterface == NetworkInterfaceType.CELLULAR) {
            RawCellularUDPSocket("0.0.0.0", 0)
        } else {
            KtorRawUDPSocket("0.0.0.0", 0)
        }

        socket.onDatagramReceived = { data, sourceAddress, sourcePort ->
            // This is data coming FROM an internet Target, going BACK to the local Client
            handleIncomingDatagram(data, sourceAddress, sourcePort)
        }

        try {
            socket.suspendBind(null, 0)
            outboundSocket = socket
            logger.debug("SOCKS5 UDP unified outbound socket created")
            return socket
        } catch (e: Exception) {
            logger.error("Failed to create SOCKS5 UDP unified outbound socket: {}", e.message)
            socket.disconnect()
            return null
        }
    }

    /**
     * Processes a datagram received from a target server.
     * We need to wrap it with a SOCKS5 UDP header and send it back to the client.
     */
    private suspend fun handleDatagramFromTarget(data: ByteArray, targetAddress: IPAddress, targetPort: Port) {
        val targetIpStr = targetAddress.presentation
        val ipParts = targetIpStr.split(".")
        
        // Only supporting IPv4 response encoding for now as it's the most common and robust.
        // For production, IPv6 encoding should also be supported based on targetAddress type.
        val isIPv4 = ipParts.size == 4
        
        val headerSize = if (isIPv4) 10 else 22 // 4(RSV,FRAG,ATYP) + IP + Port
        val responseData = ByteArray(headerSize + data.size)
        val buffer = ByteBuffer.wrap(responseData)
        
        // RSV
        buffer.putShort(0)
        // FRAG
        buffer.put(0)
        
        if (isIPv4) {
            // ATYP IPv4
            buffer.put(0x01)
            // BND.ADDR
            for (part in ipParts) {
                buffer.put(part.toInt().toByte())
            }
        } else {
            // ATYP IPv6
            buffer.put(0x04)
            // BND.ADDR (very naïve IPv6 conversion, assumes no compression)
            val parts = targetIpStr.split(":")
            // We should use an actual IP parser to get bytes for IPv6 usually, 
            // relying on standard java.net.InetAddress
            val addrBytes = java.net.InetAddress.getByName(targetIpStr).address
            if (addrBytes.size == 16) {
                buffer.put(addrBytes)
            } else {
                logger.error("Failed to parse IPv6 address into bytes: {}", targetIpStr)
                return
            }
        }
        
        // BND.PORT
        buffer.putShort(targetPort.hostOrderValue.toShort())
        
        // DATA
        buffer.put(data)
        
        logger.info("Relaying UDP: Target ({}:{}) -> Client ({}:{}) | {} bytes payload", 
            targetAddress.presentation, targetPort.hostOrderValue, 
            expectedClientAddress.presentation, expectedClientPort.hostOrderValue, 
            data.size)
            
        // Send back to the client using the main client socket
        // We use the learned client IP and port.
        val replyAddr = actualClientAddress ?: expectedClientAddress
        val replyPort = actualClientPort ?: expectedClientPort
        clientSocket?.send(responseData, replyAddr.presentation, replyPort.hostOrderValue)
    }
}
