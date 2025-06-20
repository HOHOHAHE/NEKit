import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap // Alternative to Mutex for map

// Assuming IPStackProtocol.kt, IPPacket.kt, UDPProtocolParser.kt, IPAddress.kt, Port.kt are available.
// Assuming KotlinUDPSocket.kt, KotlinUDPSocketDelegate.kt (from DNSResolver context) are available.
// Assuming ConnectSession.kt (placeholder) is available.

// --- Placeholders (ensure these are consistent with definitions elsewhere) ---
// data class IPAddress(...) // from Utils
// value class Port(...) // from Utils
// interface KotlinUDPSocket { ... }
// interface KotlinUDPSocketDelegate { ... }
// class PlaceholderUDPSocket(...) : KotlinUDPSocket // from DNSResolver context

// Refined placeholder for ConnectSession for UDPDirectStack usage
open class ConnectSession(val host: String, val port: Int) {
    // Original Swift code might have more logic in ConnectSession, e.g., for connection status.
    // This is simplified based on usage in UDPDirectStack.
    constructor(ipAddress: IPAddress, portObj: Port) : this(ipAddress.presentation, portObj.hostOrderValue.toInt()) {
        // In Swift, `session.host` and `session.port` were used for NWUDPSocket.
        // If `host` can be a domain name that needs resolution before socket creation,
        // this constructor or the socket creation logic would need to handle that.
        // For UDPDirectStack, it seems to be used directly with IPAddress.presentation.
    }
     override fun toString(): String = "ConnectSession($host:$port)"
}
// --- End Placeholders ---

data class ConnectInfo(
    val sourceAddress: IPAddress,
    val sourcePort: Port,
    val destinationAddress: IPAddress,
    val destinationPort: Port
)
// equals and hashCode are auto-generated for data class, matching Swift behavior.


/**
 * This stack transmits UDP packets directly, acting like a transparent UDP forwarder.
 * It maintains a mapping of client connections to remote UDP sockets.
 */
class UDPDirectStack : IPStackProtocol, KotlinUDPSocketDelegate {

    private val activeSockets: MutableMap<ConnectInfo, KotlinUDPSocket> = ConcurrentHashMap()
    override var outputFunc: ((packets: List<ByteArray>, versions: List<Int>) -> Unit)? = null

    // Scope for managing socket operations and cleanup tasks if they become suspending.
    // Using a dedicated dispatcher or Dispatchers.IO for socket operations.
    private val stackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Mutex for synchronizing access to activeSockets if not using ConcurrentHashMap,
    // or for complex operations that need to be atomic beyond single map calls.
    // private val socketsMutex = Mutex() // Using ConcurrentHashMap, so Mutex might be for specific compound ops only.


    constructor() {
        // Initialization if any
    }

    override fun input(packet: ByteArray, version: Int?): Boolean {
        if (version != null && version != AddressFamily.AF_INET) {
            // println("VERBOSE: UDPDirectStack: Ignoring non-IPv4 packet (version: $version).")
            return false
        }
        // TODO: Use a more robust IPPacket.peekProtocol if available and performant.
        // For now, assume it's correctly identifying UDP.
        if (IPPacket.peekProtocol(packet) == TransportProtocol.UDP) {
            // Launch processing in a separate coroutine to free up the caller (e.g., TUN read loop)
            stackScope.launch {
                processUdpPacket(packet)
            }
            return true
        }
        return false
    }

    private fun processUdpPacket(packetData: ByteArray) {
        val ipPacket: IPPacket = try {
            IPPacket(packetData) // Parses IP and UDP headers
        } catch (e: Exception) {
            System.err.println("ERROR: UDPDirectStack: Failed to parse IPPacket: ${e.message}")
            return
        }

        val udpParser = ipPacket.protocolParser as? UDPProtocolParser ?: run {
            System.err.println("ERROR: UDPDirectStack: Not a UDP packet or UDP parsing failed.")
            return
        }

        val payload = udpParser.payloadData ?: run {
            System.err.println("ERROR: UDPDirectStack: UDP packet has no payload.")
            return
        }

        val (_, socket) = findOrCreateSocketForPacket(ipPacket, udpParser)

        socket.write(data = payload)
    }

    private fun findOrCreateSocketForPacket(
        packet: IPPacket,
        udpParser: UDPProtocolParser // Pass parsed UDP info
    ): Pair<ConnectInfo, KotlinUDPSocket> {
        // Ensure source and destination addresses/ports are available from IPPacket and UDPParser
        val srcAddr = packet.sourceAddress ?: throw IllegalStateException("Packet source address missing")
        val srcPort = udpParser.sourcePort ?: throw IllegalStateException("Packet source port missing")
        val dstAddr = packet.destinationAddress ?: throw IllegalStateException("Packet destination address missing")
        val dstPort = udpParser.destinationPort ?: throw IllegalStateException("Packet destination port missing")

        val connectInfo = ConnectInfo(srcAddr, srcPort, dstAddr, dstPort)

        // ConcurrentHashMap.get is thread-safe.
        // For complex logic (check-then-put), use computeIfAbsent for atomicity.
        return activeSockets.computeIfAbsent(connectInfo) { keyInfo ->
            println("INFO: UDPDirectStack: Creating new UDP socket for $keyInfo")
            // The Swift code uses ConnectSession to derive host/port for NWUDPSocket.
            // If destinationAddress is always an IP, ConnectSession just wraps it.
            val sessionForSocket = ConnectSession(keyInfo.destinationAddress, keyInfo.destinationPort)

            // TODO: Replace PlaceholderUDPSocket with actual UDP socket implementation.
            // The actual socket should be configured to send to keyInfo.destinationAddress:keyInfo.destinationPort
            // and receive responses. For a client-like UDP socket, this might mean connect() or just sendTo().
            // For this model, each "connection" gets its own socket.
            val newUdpSocket = PlaceholderUDPSocket(sessionForSocket.host, sessionForSocket.port)
            newUdpSocket.delegate = this@UDPDirectStack
            // If the socket needs explicit connection or binding:
            // newUdpSocket.connect()
            newUdpSocket // This is the value returned to computeIfAbsent
        } to activeSockets[connectInfo]!! // Return the pair (keyInfo, createdOrExistingSocket)
    }


    // Implementation of KotlinUDPSocketDelegate
    override fun didReceive(data: ByteArray, from: KotlinUDPSocket) {
        // Find which ConnectInfo this socket belongs to.
        // This requires iterating if `from` is the only info.
        val entry = activeSockets.entries.find { it.value === from } // Find by socket instance
        if (entry == null) {
            System.err.println("ERROR: UDPDirectStack: Received data on unknown or closed socket.")
            return
        }
        val connectInfo = entry.key

        // Construct reply IP packet
        val replyIpPacket = IPPacket() // For building
        replyIpPacket.sourceAddress = connectInfo.destinationAddress // Original dest is now src
        replyIpPacket.destinationAddress = connectInfo.sourceAddress   // Original src is now dest
        replyIpPacket.transportProtocol = TransportProtocol.UDP

        val replyUdpParser = UDPProtocolParserImpl() // Using placeholder Impl for now
        replyUdpParser.sourcePort = connectInfo.destinationPort // Original dest port is now src
        replyUdpParser.destinationPort = connectInfo.sourcePort   // Original src port is now dest
        replyUdpParser.payloadData = data

        replyIpPacket.protocolParser = replyUdpParser

        try {
            replyIpPacket.buildPacket() // Builds UDP segment and then IP packet
        } catch (e: Exception) {
            System.err.println("ERROR: UDPDirectStack: Failed to build reply IP packet: ${e.message}")
            return
        }

        replyIpPacket.packetData?.let { builtPacketData ->
            val version = if (replyIpPacket.version == IPVersion.IPv4) AddressFamily.AF_INET else AddressFamily.AF_INET6
            outputFunc?.invoke(listOf(builtPacketData), listOf(version))
        } ?: System.err.println("ERROR: UDPDirectStack: Built reply packet data is null.")
    }

    override fun didCancel(socket: KotlinUDPSocket) {
        // Called when a socket is closed (e.g., by remote, error, or explicit disconnect)
        val entry = activeSockets.entries.find { it.value === socket }
        if (entry != null) {
            activeSockets.remove(entry.key)
            println("INFO: UDPDirectStack: Removed active socket for ${entry.key} due to cancellation/closure.")
        } else {
            // println("INFO: UDPDirectStack: didCancel called for an already removed or unknown socket.")
        }
    }

    override fun start() {
        println("INFO: UDPDirectStack started.")
        // No specific startup actions like binding a listening server socket,
        // as sockets are created on-demand for outgoing connections.
    }

    override fun stop() {
        println("INFO: UDPDirectStack stopping...")
        val currentSockets = ArrayList(activeSockets.values) // Avoid ConcurrentModificationException
        activeSockets.clear()

        stackScope.launch { // Perform disconnects asynchronously
            for (socket in currentSockets) {
                socket.disconnect() // This should trigger didCancel if socket impl calls it
            }
        }
        stackScope.cancel("UDPDirectStack stopped") // Cancel any ongoing tasks in this scope
        println("INFO: UDPDirectStack stopped. All active sockets signaled to disconnect.")
    }
}
