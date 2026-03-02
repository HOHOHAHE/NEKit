package com.example.nekit.ProxyServer

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.KtorRawUDPSocket
import com.example.nekit.RawSocket.RawUDPSocketProtocol
import com.example.nekit.Socket.ProxySocket.SOCKS5ProxySocket
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
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
    private val socks5ProxySocket: SOCKS5ProxySocket
) {
    private val logger = LoggerFactory.getLogger(SOCKS5UDPRelayServer::class.java)
    
    // The active socket listening for UDP datagrams
    private var relaySocket: RawUDPSocketProtocol? = null
    
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
            // Use 0 for an ephemeral port, and bind to all interfaces (0.0.0.0 or ::)
            val socket = KtorRawUDPSocket("0.0.0.0", 0)
            
            // Set up our callback listener BEFORE binding so we don't miss packets
            socket.onDatagramReceived = { data, sourceAddress, sourcePort ->
                handleIncomingDatagram(data, sourceAddress, sourcePort)
            }
            
            // Wait for it to bind
            socket.bind(null, 0)
            
            // Retrieve the bound port info
            val localAddr = socket.localAddress
            val localPort = socket.sourcePort
            
            if (localAddr != null && localPort != null) {
                boundAddress = localAddr
                boundPort = localPort
                relaySocket = socket
                logger.info("SOCKS5 UDP Relay started on {}:{}", boundAddress?.presentation, boundPort?.hostOrderValue)
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
        relaySocket?.disconnect()
        relaySocket = null
    }

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
                // but checking against the expected client is the correct default behavior.
                val isFromClient = (sourceAddress.presentation == expectedClientAddress.presentation)
                
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
        
        logger.debug("Relaying UDP: Client -> Target ({}:{}) | {} bytes payload", destHostStr, destPortInt, payloadData.size)
        
        // Forward the actual payload to the target server using the relay socket
        // Note: For UDP relay, we just use the existing socket to send to the destination host/port directly.
        relaySocket?.send(payloadData, destHostStr, destPortInt)
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
        
        logger.debug("Relaying UDP: Target ({}:{}) -> Client ({}:{}) | {} bytes payload", 
            targetAddress.presentation, targetPort.hostOrderValue, 
            expectedClientAddress.presentation, expectedClientPort.hostOrderValue, 
            data.size)
            
        // Send back to the client
        // We use the client's expected IP and Port.
        relaySocket?.send(responseData, expectedClientAddress.presentation, expectedClientPort.hostOrderValue)
    }
}
