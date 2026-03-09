package nekit.IPStack
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap // Alternative to Mutex for map
import java.lang.ref.WeakReference // Import WeakReference

import org.slf4j.LoggerFactory

import nekit.Utils.IPAddress
import nekit.Utils.Port
import nekit.RawSocket.protocol.RawUDPSocketProtocol
import nekit.RawSocket.protocol.RawUDPSocketDelegate
import nekit.RawSocket.protocol.RawSocketFactory
import nekit.Utils.PlatformDetector
import nekit.Messages.ConnectSession
import nekit.IPStack.Packet.IPPacket
import nekit.IPStack.Packet.UDPProtocolParser
import nekit.IPStack.Packet.IPPacketImpl // Import IPPacketImpl
import nekit.Config.NetworkInterfaceType
import nekit.Config.GlobalNetworkManager

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
class UDPDirectStack : IPStackProtocol, RawUDPSocketDelegate {

    private val logger = LoggerFactory.getLogger(UDPDirectStack::class.java)
    private val activeSockets: MutableMap<ConnectInfo, RawUDPSocketProtocol> = ConcurrentHashMap()
    override var outputFunc: ((packets: List<ByteArray>, versions: List<AddressFamily>) -> Unit)? = null

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
        if (version != null && version != AddressFamily.AF_INET.value) { // Compare Int with Int value
            // println("VERBOSE: UDPDirectStack: Ignoring non-IPv4 packet (version: $version).")
            return false
        }
        // TODO: Use a more robust IPPacket.peekProtocol if available and performant.
        // For now, assume it's correctly identifying UDP.
        if (IPPacket.Companion.peekProtocol(packet) == TransportProtocol.UDP) {
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
            IPPacketImpl(packetData) // Parses IP and UDP headers
        } catch (e: Exception) {
            logger.error("Failed to parse IPPacket: {}", e.message, e)
            return
        }

        val udpParser = ipPacket.protocolParser as? UDPProtocolParser ?: run {
            logger.error("Not a UDP packet or UDP parsing failed for packet from {}.", ipPacket.sourceAddress)
            return
        }

        val payload = udpParser.payloadData ?: run {
            logger.error("UDP packet from {} has no payload.", ipPacket.sourceAddress)
            return
        }

        val socket = findOrCreateSocketForPacket(ipPacket, udpParser)

        socket.write(data = payload)
    }

    private fun findOrCreateSocketForPacket(
        packet: IPPacket,
        udpParser: UDPProtocolParser // Pass parsed UDP info
    ): RawUDPSocketProtocol {
        // Ensure source and destination addresses/ports are available from IPPacket and UDPParser
        val srcAddr = packet.sourceAddress ?: throw IllegalStateException("Packet source address missing")
        val srcPort = udpParser.sourcePort ?: throw IllegalStateException("Packet source port missing")
        val dstAddr = packet.destinationAddress ?: throw IllegalStateException("Packet destination address missing")
        val dstPort = udpParser.destinationPort ?: throw IllegalStateException("Packet destination port missing")

        val connectInfo = ConnectInfo(srcAddr, srcPort, dstAddr, dstPort)

        val socket = activeSockets.getOrPut(connectInfo) {
            logger.info("Creating new UDP socket for {}", connectInfo)
            // On Android + CELLULAR: use RawCellularUDPSocket (network.bindSocket capable)
            // On macOS runLocal: always fall back to RawUDPSocket
            val newUdpSocket = RawSocketFactory.getRawUDPSocket(
                connectInfo.destinationAddress.presentation,
                connectInfo.destinationPort.hostOrderValue
            )
            newUdpSocket.delegate = WeakReference(this@UDPDirectStack)
            newUdpSocket
        }
        return socket
    }


    // Implementation of KotlinUDPSocketDelegate
    override fun didReceive(data: ByteArray, from: RawUDPSocketProtocol) {
        // Find which ConnectInfo this socket belongs to.
        // This requires iterating if `from` is the only info.
        val entry = activeSockets.entries.find { it.value === from } // Find by socket instance
        if (entry == null) {
            logger.error("Received data on unknown or closed socket: {}", from)
            return
        }
        val connectInfo = entry.key

        // Construct reply IP packet
        val replyIpPacket = IPPacketImpl() // For building
        replyIpPacket.sourceAddress = connectInfo.destinationAddress // Original dest is now src
        replyIpPacket.destinationAddress = connectInfo.sourceAddress   // Original src is now dest
        replyIpPacket.transportProtocol = TransportProtocol.UDP

        val replyUdpParser = nekit.IPStack.Packet.UDPProtocolParserImpl() // Using placeholder Impl for now
        replyUdpParser.sourcePort = connectInfo.destinationPort // Original dest port is now src
        replyUdpParser.destinationPort = connectInfo.sourcePort   // Original src port is now dest
        replyUdpParser.payloadData = data

        replyIpPacket.protocolParser = replyUdpParser

        try {
            replyIpPacket.buildPacket() // Builds UDP segment and then IP packet
        } catch (e: Exception) {
            logger.error("Failed to build reply IP packet for {}: {}", connectInfo, e.message, e)
            return
        }

        replyIpPacket.packetData?.let { builtPacketData ->
            val version = if (replyIpPacket.version == nekit.IPStack.IPVersion.IPV4) AddressFamily.AF_INET else AddressFamily.AF_INET6 // Use IPv4
            outputFunc?.invoke(listOf(builtPacketData), listOf(version))
        } ?: logger.error("Built reply packet data is null for {}.", connectInfo)
    }

    override fun didCancel(socket: RawUDPSocketProtocol) {
        // Called when a socket is closed (e.g., by remote, error, or explicit disconnect)
        val entry = activeSockets.entries.find { it.value === socket }
        if (entry != null) {
            activeSockets.remove(entry.key)
            logger.info("Removed active socket for {} due to cancellation/closure.", entry.key)
        } else {
            // logger.info("didCancel called for an already removed or unknown socket: {}", socket)
        }
    }

    override fun start() {
        logger.info("UDPDirectStack started.")
        // No specific startup actions like binding a listening server socket,
        // as sockets are created on-demand for outgoing connections.
    }

    override fun stop() {
        logger.info("UDPDirectStack stopping...")
        val currentSockets = ArrayList(activeSockets.values) // Avoid ConcurrentModificationException
        activeSockets.clear()

        stackScope.launch { // Perform disconnects asynchronously
            for (socket in currentSockets) {
                socket.disconnect() // This should trigger didCancel if socket impl calls it
            }
        }
        stackScope.cancel("UDPDirectStack stopped") // Cancel any ongoing tasks in this scope
        logger.info("UDPDirectStack stopped. All active sockets signaled to disconnect.")
    }
    override fun didErrorOccur(error: Throwable, onSocket: RawUDPSocketProtocol) {
        logger.error("Error occurred on UDP socket: {}", error.message, error)
        // Handle error, e.g., remove the socket from activeSockets if it's a fatal error
        val entry = activeSockets.entries.find { it.value === onSocket }
        if (entry != null) {
            activeSockets.remove(entry.key)
            logger.info("Removed active socket for {} due to error.", entry.key)
        }
    }
}
