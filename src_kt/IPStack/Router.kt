import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap // For thread-safe NAT table

// Assuming IPAddress.kt, Port.kt, IPMutablePacket.kt, TCPMutablePacket.kt,
// IPVersion.kt, TransportProtocol.kt are available.
// TODO: Replace CocoaLumberjack with a Kotlin logging solution.

// --- Placeholder for NetworkInterface.TunnelProvider.packetFlow ---
// This represents the TUN/TAP interface interaction.
interface KotlinTunnelFlow {
    /**
     * Reads packets from the tunnel. The completion handler is called with the packets and their protocols.
     * This would be a suspending function in a more idiomatic coroutine design.
     */
    fun readPackets(completion: (packets: List<ByteArray>, protocols: List<Int>) -> Unit)

    /**
     * Writes packets to the tunnel.
     */
    fun writePackets(packets: List<ByteArray>, protocols: List<Int>)
}

// Example placeholder implementation for KotlinTunnelFlow
// TODO: Replace with actual JNI/JNA based TUN/TAP implementation.
object NetworkInterface { // Mimicking Swift structure
    object TunnelProvider { // Mimicking Swift structure
        val packetFlow: KotlinTunnelFlow = object : KotlinTunnelFlow {
            private var isReading = false
            override fun readPackets(completion: (packets: List<ByteArray>, protocols: List<Int>) -> Unit) {
                if (isReading) { // Basic guard against concurrent reads if not supported by underlying impl
                    println("WARN: PlaceholderPacketFlow: readPackets called while already reading.")
                    // To avoid tight loop in Router's readAndProcessPackets, schedule a delayed empty completion
                    GlobalScope.launch { delay(100); completion(emptyList(), emptyList()) }
                    return
                }
                isReading = true
                println("INFO: PlaceholderPacketFlow: readPackets called. (TODO: Implement actual TUN read)")
                // Simulate asynchronous read with a delay and dummy packet for testing structure
                GlobalScope.launch {
                    delay(1000) // Simulate network delay
                    // Create a dummy TCP/IP packet for testing the router logic
                    // val dummyTcpPayload = "Hello".toByteArray()
                    // val dummyUdpParser = UDPProtocolParserImpl().apply { payloadData = dummyTcpPayload } // Incorrect for TCP
                    // val ipPacket = IPPacket().apply {
                    //     sourceAddress = IPAddress.parse("192.168.1.100")
                    //     destinationAddress = IPAddress.parse("10.0.0.1") // interfaceIP for one path
                    //     transportProtocol = TransportProtocol.TCP
                    //     // protocolParser = dummyTcpParser... (would need TCP parser)
                    // }
                    // ipPacket.buildPacket()
                    // val packets = ipPacket.packetData?.let { listOf(it) } ?: emptyList()
                    // val protocols = if (packets.isNotEmpty()) listOf(AddressFamily.AF_INET) else emptyList()
                    val packets = emptyList<ByteArray>() // Default to no packets for placeholder
                    val protocols = emptyList<Int>()
                    completion(packets, protocols)
                    isReading = false
                }
            }

            override fun writePackets(packets: List<ByteArray>, protocols: List<Int>) {
                println("INFO: PlaceholderPacketFlow: Writing ${packets.size} packets. (TODO: Implement actual TUN write)")
                packets.forEachIndexed { index, bytes ->
                    println("  Packet ${index + 1}: ${bytes.size} bytes, Proto: ${protocols.getOrNull(index) ?: "N/A"}")
                }
            }
        }
    }
}
// --- End Placeholder ---

class Router(
    interfaceIpString: String,
    fakeSourceIpString: String,
    proxyServerIpString: String,
    proxyServerPortValue: UShort // Changed from UInt16 for consistency with Port.kt
) {
    // Using ConcurrentHashMap for thread-safety, as NEPacketTunnelFlow might use multiple threads.
    private val ipv4NatRoutes: MutableMap<Port, Pair<IPAddress, Port>> = ConcurrentHashMap()
    private val interfaceIP: IPAddress = IPAddress.parse(interfaceIpString)
        ?: throw IllegalArgumentException("Invalid interface IP: $interfaceIpString")
    private val fakeSourceIP: IPAddress = IPAddress.parse(fakeSourceIpString)
        ?: throw IllegalArgumentException("Invalid fake source IP: $fakeSourceIpString")
    private val proxyServerIP: IPAddress = IPAddress.parse(proxyServerIpString)
        ?: throw IllegalArgumentException("Invalid proxy server IP: $proxyServerIpString")
    private val proxyServerPort: Port = Port(proxyServerPortValue)

    // Coroutine scope for managing the packet processing loop
    private val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // IO dispatcher for network operations

    /**
     * Rewrites TCP/IPv4 packets based on NAT rules.
     * @param packet The mutable TCP packet to rewrite.
     * @return The rewritten packet, or null if the packet should be dropped or is not handled.
     */
    fun rewritePacket(packet: TCPMutablePacket): TCPMutablePacket? {
        // Original Swift logic checked packet.proto == .TCP, which is implicitly true
        // if we receive a TCPMutablePacket due to its init check.
        // Also, IPMutablePacket was IPv4 only.

        // Path 1: Packet coming from internal interface, going out
        if (packet.sourceAddress == interfaceIP) {
            if (packet.sourcePort == proxyServerPort) { // Packet from our proxy server, going back to original sender
                val (originalDestAddress, originalDestPort) = ipv4NatRoutes.remove(packet.destinationPort) ?: run {
                    // If destinationPort is not found in NAT table, it means this is not a return packet we know.
                    // This could be an unsolicited packet from proxyServerPort to an unknown internal client port.
                    System.err.println("ERROR: Router: No NAT entry for return packet on proxy port ${packet.destinationPort}. Dropping. Packet: ${packet.sourceAddress}:${packet.sourcePort} -> ${packet.destinationAddress}:${packet.destinationPort}")
                    return null
                }
                // Restore original destination as source, and interfaceIP as destination
                packet.sourcePort = originalDestPort
                packet.sourceAddress = originalDestAddress
                packet.destinationAddress = interfaceIP // Send back to original requester via interface IP
                println("INFO: Router: Rewriting proxy response: ${packet.sourceAddress}:${packet.sourcePort} -> ${packet.destinationAddress}:${packet.destinationPort} (orig NAT key: ${packet.destinationPort})")

            } else { // Packet from an internal client, going to an external destination (needs NAT)
                // Store original (source IP, source port) using its source port as key for return traffic.
                // The value stored should be (original source IP, original source port)
                // The Swift code stores (packet.destinationAddress, packet.destinationPort) using packet.sourcePort as key.
                // This means when response comes *to* proxyServerPort *from* external server, its *source port* will be proxyServerPort's ephemeral port.
                // No, the Swift logic for return path: `IPv4NATRoutes[packet.destinationPort]`.
                // This means the key used is the *destination port* of the *return packet* from the proxy.
                // When sending *out*, `packet.sourcePort` is the original client's port.
                // This port needs to be used as the key if the proxy server preserves it or if we map it.
                // The Swift code `IPv4NATRoutes[packet.sourcePort] = (packet.destinationAddress, packet.destinationPort)` is for the *outgoing* packet's original destination.
                // Let's re-evaluate the NAT logic carefully.
                // Standard NAT: (internal_src_ip, internal_src_port, internal_dst_ip, internal_dst_port)
                // -> (router_external_ip, router_new_src_port, external_dst_ip, external_dst_port)
                // Key for return: (router_new_src_port) -> (internal_src_ip, internal_src_port)

                // Swift logic:
                // Outgoing (client -> external):
                //   Key: packet.sourcePort (client's port)
                //   Value: (packet.destinationAddress, packet.destinationPort) (original external destination)
                //   Rewrite: src = fakeSourceIP, dst = proxyServerIP, dstPort = proxyServerPort
                // Incoming (proxy -> client via router): (This is the `if packet.sourcePort == proxyServerPort` block)
                //   Packet comes from proxy (src=proxyServerIP, srcPort=proxyServerPort's ephemeral port for this client connection)
                //   To router (dst=interfaceIP, dstPort= ??? -> this is the key for NAT lookup)
                //   The key `packet.destinationPort` in the return path implies that the *client's original source port* was used as the *destination port* for traffic returning to the client *after* NAT by this router.
                //   This is unusual. Typically, the router's NAT port (that replaced client's source port) is the key.

                // Let's assume the Swift logic is specific to its environment.
                // For now, will replicate Swift logic:
                // Key for NAT table: client's original source port.
                // Value in NAT table: original external destination (IP, Port).
                ipv4NatRoutes[packet.sourcePort] = Pair(packet.destinationAddress, packet.destinationPort)
                println("INFO: Router: Storing NAT entry for ${packet.sourcePort} -> (${packet.destinationAddress}, ${packet.destinationPort})")

                // Rewrite packet to go through proxy
                packet.sourceAddress = fakeSourceIP // Masquerade client IP
                packet.destinationAddress = proxyServerIP
                packet.destinationPort = proxyServerPort
                 println("INFO: Router: Rewriting client packet: ${packet.sourceAddress}:${packet.sourcePort} -> ${packet.destinationAddress}:${packet.destinationPort}")
            }
        } else { // Packet not from interfaceIP, unexpected scenario based on Swift logic.
            System.err.println("ERROR: Router: Packet source ${packet.sourceAddress} is not interface IP ${interfaceIP}. Dropping.")
            return null
        }
        // Recalculate checksums after modifications
        // IPMutablePacket setters for address/port should handle IP checksum.
        // TCPMutablePacket setters for port should handle TCP checksum.
        // If addresses also changed, TCP checksum also needs full recalc due to pseudo-header.
        packet.recalculateTCPChecksum() // Ports and IPs changed, TCP checksum needs full update.
                                     // IPMutablePacket's address setters should already update IP checksum.
        return packet
    }

    /**
     * Starts the packet processing loop.
     * This should be called on a background thread or within a coroutine.
     */
    fun startProcessingPackets() {
        if (!routerScope.isActive) {
            System.err.println("ERROR: Router scope is not active. Cannot start packet processing.")
            return
        }
        println("INFO: Router: Starting packet processing loop.")
        routerScope.launch {
            while (isActive) { // Loop while the scope is active
                // Using a CompletableDeferred to bridge callback to suspending function style for read
                val readResult = CompletableDeferred<Pair<List<ByteArray>, List<Int>>>()
                NetworkInterface.TunnelProvider.packetFlow.readPackets { packets, protocols ->
                    readResult.complete(Pair(packets, protocols))
                }

                try {
                    val (packetsData, protocols) = readResult.await() // Suspend until read completes
                    if (packetsData.isEmpty() && protocols.isEmpty()) {
                        // Potentially a signal to yield or a short delay if no packets,
                        // or the placeholder flow just completed without data.
                        // println("DEBUG: Router: No packets read, or placeholder flow completed.")
                        delay(10) // Avoid tight loop if readPackets completes immediately with no data
                        continue
                    }

                    val outputPacketsData = mutableListOf<ByteArray>()
                    val outputProtocols = mutableListOf<Int>()

                    packetsData.forEachIndexed { index, data ->
                        try {
                            // Attempt to parse as IPMutablePacket (which expects IPv4) then TCPMutablePacket
                            val ipMutablePacket = IPMutablePacket(data.copyOf()) // Use copy to avoid modifying original read buffer
                            if (ipMutablePacket.version == IPVersion.IPv4 && ipMutablePacket.protocol == TransportProtocol.TCP) {
                                val tcpPacket = TCPMutablePacket(ipMutablePacket.getPacketData()) // Pass the same ByteArray

                                println("VERBOSE: Router: Received TCPv4 packet: ${tcpPacket.sourceAddress}:${tcpPacket.sourcePort} -> ${tcpPacket.destinationAddress}:${tcpPacket.destinationPort}")
                                rewritePacket(tcpPacket)?.let { rewrittenPacket ->
                                    outputPacketsData.add(rewrittenPacket.getPacketData())
                                    outputProtocols.add(AddressFamily.AF_INET) // Assuming AF_INET from context
                                } ?: println("VERBOSE: Router: Packet dropped or not rewritten: ${tcpPacket.sourceAddress}:${tcpPacket.sourcePort}")
                            } else {
                                println("VERBOSE: Router: Skipping non-TCP/IPv4 packet. Version: ${ipMutablePacket.version}, Proto: ${ipMutablePacket.protocol}")
                            }
                        } catch (e: Exception) {
                            System.err.println("ERROR: Router: Error processing packet at index $index: ${e.message}")
                            // Optionally, log packet details if possible (e.g., first few bytes)
                        }
                    }

                    if (outputPacketsData.isNotEmpty()) {
                        println("INFO: Router: Writing out ${outputPacketsData.size} packets.")
                        NetworkInterface.TunnelProvider.packetFlow.writePackets(outputPacketsData, outputProtocols)
                    }
                } catch (e: CancellationException) {
                    println("INFO: Router: Packet processing loop cancelled.")
                    break // Exit loop
                } catch (e: Exception) {
                    System.err.println("ERROR: Router: Error in packet processing loop: ${e.message}")
                    delay(100) // Avoid tight loop on persistent errors
                }
            }
            println("INFO: Router: Packet processing loop finished.")
        }
    }

    /**
     * Stops the packet processing loop.
     */
    fun stop() {
        println("INFO: Router: Stopping packet processing...")
        routerScope.cancel("Router stopped")
        // Clear NAT table if desired, though it's instance-specific
        // ipv4NatRoutes.clear()
    }
}
