package nekit.Socket.ProxySocket

import nekit.Messages.ConnectSession
import nekit.ProxyServer.SOCKS5UDPRelayServer
import nekit.RawSocket.protocol.RawTCPSocketProtocol
import nekit.Socket.AdapterSocket.AdapterSocket
import nekit.Socket.SocketStatus
import nekit.Utils.IPAddress
import nekit.Utils.Port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import nekit.Config.NetworkInterfaceType

class SOCKS5ProxySocket(
    rawSocket: RawTCPSocketProtocol
) : ProxySocket(rawSocket) {

    override var session: ConnectSession? = null
        public set

    var outboundInterfaceType: NetworkInterfaceType = NetworkInterfaceType.DEFAULT

    /// The address the SOCKS5 server is listening on. Used as BND.ADDR in UDP ASSOCIATE replies.
    var serverAddress: IPAddress? = null

    private val logger = LoggerFactory.getLogger(SOCKS5ProxySocket::class.java)
    private var state = State.INITIAL
    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // For storing the relay server if it's a UDP associate connection
    private var udpRelayServer: SOCKS5UDPRelayServer? = null
    // To identify if the current connection is for UDP
    private var isUdpAssociate = false

    private enum class State {
        INITIAL,
        GREETING,
        READING_METHODS,
        CONNECTING,
        READING_IPV4,
        READING_IPV6,
        READING_DOMAIN_LENGTH,
        READING_DOMAIN,
        READING_PORT,
        SENDING_RESPONSE,
        FORWARDING
    }

    override fun openSocket() {
        super.openSocket()
        state = State.GREETING
        // Start by reading exactly 2 bytes for version and number of methods
        rawSocket.readDataTo(2)
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        when (state) {
            State.GREETING -> {
                handleGreeting(data)
            }
            State.READING_METHODS -> {
                handleMethods(data)
            }
            State.CONNECTING -> {
                handleConnect(data)
            }
            State.READING_IPV4 -> {
                handleIPv4Address(data)
            }
            State.READING_IPV6 -> {
                handleIPv6Address(data)
            }
            State.READING_DOMAIN_LENGTH -> {
                handleDomainLength(data)
            }
            State.READING_DOMAIN -> {
                handleDomain(data)
            }
            State.READING_PORT -> {
                handlePort(data)
            }
            State.FORWARDING -> {
                delegate?.get()?.didRead(data, this)
            }
            else -> return
        }
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by)
        when (state) {
            State.SENDING_RESPONSE -> {
                state = State.FORWARDING
                _status = SocketStatus.ESTABLISHED
                delegate?.get()?.didBecomeReadyToForward(this)
            }
            State.FORWARDING -> {
                delegate?.get()?.didWrite(data, this)
            }
            else -> return
        }
    }

    private fun handleGreeting(data: ByteArray) {
        // Should receive exactly 2 bytes: version and number of methods
        if (data.size < 2) {
            forceDisconnect(IOException("Invalid SOCKS5 greeting: insufficient data"))
            return
        }
        
        val version = data[0]
        val nMethods = data[1].toInt() and 0xFF
        
        if (version != 0x05.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 version: $version"))
            return
        }
        
        if (nMethods <= 0) {
            forceDisconnect(IOException("Invalid number of methods: $nMethods"))
            return
        }
        
        // Read the methods
        rawSocket.readDataTo(nMethods) 
        state = State.READING_METHODS
    }
    
    private fun handleMethods(data: ByteArray) {
        // TODO: check for 0x00 in read data
        val response = byteArrayOf(0x05, 0x00) // NO AUTH
        write(response)
        // After sending response, read connect header (4 bytes)
        rawSocket.readDataTo(4)
        state = State.CONNECTING
    }

    private fun handleConnect(data: ByteArray) {
        // Should receive exactly 4 bytes: VER, CMD, RSV, ATYP
        if (data.size < 4) {
            forceDisconnect(IOException("Invalid SOCKS5 connect header: insufficient data"))
            return
        }
        
        val version = data[0]
        val cmd = data[1]
        // Skip RSV (data[2])
        val atyp = data[3]
        
        if (version != 0x05.toByte()) {
            forceDisconnect(IOException("Invalid SOCKS5 connect request: version=$version"))
            return
        }
        
        if (cmd == 0x01.toByte()) {
            isUdpAssociate = false
        } else if (cmd == 0x03.toByte()) {
            isUdpAssociate = true
        } else {
            // We only support CONNECT (0x01) and UDP ASSOCIATE (0x03)
            forceDisconnect(IOException("Unsupported SOCKS5 command: $cmd"))
            return
        }
        
        // Based on address type, read the appropriate amount of data
        when (atyp) {
            0x01.toByte() -> { // IPv4 - read 4 bytes for IP
                state = State.READING_IPV4
                rawSocket.readDataTo(4) 
            }
            0x03.toByte() -> { // Domain - read 1 byte for length first
                state = State.READING_DOMAIN_LENGTH
                rawSocket.readDataTo(1) 
            }
            0x04.toByte() -> { // IPv6 - read 16 bytes for IP
                state = State.READING_IPV6
                rawSocket.readDataTo(16) 
            }
            else -> {
                forceDisconnect(IOException("Unsupported address type in SOCKS5 request: $atyp"))
            }
        }
    }
    
    private var destinationHost: String? = null
    private var targetPort: Int? = null
    
    private fun handleIPv4Address(data: ByteArray) {
        if (data.size < 4) {
            forceDisconnect(IOException("Invalid IPv4 address data"))
            return
        }
        destinationHost = data.joinToString(".") { (it.toInt() and 0xFF).toString() }
        state = State.READING_PORT
        rawSocket.readDataTo(2)
    }
    
    private fun handleIPv6Address(data: ByteArray) {
        if (data.size < 16) {
            forceDisconnect(IOException("Invalid IPv6 address data"))
            return
        }
        destinationHost = data.asList().chunked(2).joinToString(":") {
            String.format("%02x%02x", it[0], it[1])
        }
        state = State.READING_PORT
        rawSocket.readDataTo(2)
    }
    
    private fun handleDomainLength(data: ByteArray) {
        if (data.isEmpty()) {
            forceDisconnect(IOException("Invalid domain length data"))
            return
        }
        val domainLength = data[0].toInt() and 0xFF
        state = State.READING_DOMAIN
        rawSocket.readDataTo(domainLength)
    }
    
    private fun handleDomain(data: ByteArray) {
        destinationHost = String(data)
        state = State.READING_PORT
        rawSocket.readDataTo(2)
    }
    
    private fun handlePort(data: ByteArray) {
        if (data.size < 2) {
            forceDisconnect(IOException("Invalid port data"))
            return
        }
        targetPort = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        
        val host = destinationHost
        val port = targetPort
        if (host != null && port != null) {
            val requestSession = ConnectSession(host = host, port = port, interfaceType = outboundInterfaceType)
            this.session = requestSession
            
            if (isUdpAssociate) {
                // Determine client's assumed IP and Port from the connection itself
                // (Using the TCP source IP as best-effort for UDP source if the client sent 0.0.0.0)
                val clientIP = rawSocket.sourceIPAddress ?: IPAddress.parse("0.0.0.0")!!
                var clientPortParam: Port = Port(port)
                // If the client sent 0.0.0.0:0, it means any IP and Port might be used. 
                // We'll trust our SOCKS5UDPRelayServer's default acceptance logic.
                
                startUdpRelay(clientIP, clientPortParam)
            } else {
                // 不要立即进入FORWARDING状态，等待respondTo被调用
                delegate?.get()?.didReceive(requestSession, this)
            }
        } else {
            forceDisconnect(IOException("Missing host or port information"))
        }
    }
    
    private fun startUdpRelay(clientIP: IPAddress, clientPort: Port) {
        socketScope.launch {
            val relayServer = SOCKS5UDPRelayServer(clientIP, clientPort, this@SOCKS5ProxySocket, outboundInterfaceType, bindAddress = serverAddress)
            val success = relayServer.start()
            
            if (success) {
                udpRelayServer = relayServer
                val boundIPStr = serverAddress?.presentation ?: "0.0.0.0"
                val boundPortInt = relayServer.boundPort?.hostOrderValue ?: 0
                
                // Reply to the client with the IP and Port they should send UDP datagrams to
                sendUdpAssociateSuccessResponse(boundIPStr, boundPortInt)
            } else {
                logger.error("Failed to start UDP relay server.")
                // Send general SOCKS server failure reply
                write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                forceDisconnect(IOException("Failed to start UDP Relay"))
            }
        }
    }
    
    private fun sendUdpAssociateSuccessResponse(boundIP: String, boundPort: Int) {
        val ipParts = boundIP.split(".")
        if (ipParts.size == 4) {
            // IPv4
            val response = ByteArray(10)
            response[0] = 0x05 // VER
            response[1] = 0x00 // REP (Success)
            response[2] = 0x00 // RSV
            response[3] = 0x01 // ATYP (IPv4)
            for (i in 0..3) {
                response[4 + i] = ipParts[i].toInt().toByte()
            }
            response[8] = ((boundPort shr 8) and 0xFF).toByte()
            response[9] = (boundPort and 0xFF).toByte()
            
            state = State.SENDING_RESPONSE
            write(response)
        } else {
            // Simplistic fallback for IPv6
            val response = ByteArray(10)
            response[0] = 0x05 // VER
            response[1] = 0x00 // REP (Success)
            response[2] = 0x00 // RSV
            response[3] = 0x01 // ATYP (IPv4)
            // returning 0.0.0.0 for IP
            response[4] = 0; response[5] = 0; response[6] = 0; response[7] = 0;
            response[8] = ((boundPort shr 8) and 0xFF).toByte()
            response[9] = (boundPort and 0xFF).toByte()
            state = State.SENDING_RESPONSE
            write(response)
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        // Do NOT call super.respondTo() - that would fire didBecomeReadyToForward immediately.
        // Instead, we manually signal the observer and send the SOCKS5 success reply.
        // didBecomeReadyToForward will be fired exactly once in didWrite when
        // state transitions from SENDING_RESPONSE → FORWARDING.
        if (isCancelled) {
            logger.warn("respondTo called on a cancelled socket for session: {}", session)
            return
        }
        logger.info("respondTo called with adapter {} for session {}.", adapter, session)
        observer?.signal(nekit.Event.Event.ProxySocketEvent.AskedToResponseTo(adapter, this))
        // SOCKS5 success reply: VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
        val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        state = State.SENDING_RESPONSE
        write(response)
    }
    override fun forceDisconnect(becauseOf: Throwable?) {
        socketScope.cancel()
        udpRelayServer?.stop()
        udpRelayServer = null
        super.forceDisconnect(becauseOf)
    }
}
