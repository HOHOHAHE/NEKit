package com.example.nekit.IPStack.DNS

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

import org.slf4j.LoggerFactory

import com.example.nekit.Tunnel.QueueFactory
import com.example.nekit.IPStack.Packet.IPPacket
import com.example.nekit.IPStack.Packet.TransportProtocol
import com.example.nekit.IPStack.Packet.UDPProtocolParserImpl
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Utils.IPPool
import com.example.nekit.IPStack.IPStackProtocol
import com.example.nekit.IPStack.Native.AddressFamily
import com.example.nekit.Rule.RuleManager
import com.example.nekit.Opt
import com.example.nekit.IPStack.DNS.DNSEnums.DNSType
import com.example.nekit.IPStack.DNS.DNSEnums.DNSMessage
import com.example.nekit.IPStack.DNS.DNSEnums.DNSResource
import com.example.nekit.IPStack.DNS.DNSEnums.DNSMessageType
import com.example.nekit.IPStack.DNS.DNSEnums.DNSReturnCode

// No longer a placeholder, as DNSSession.kt is now a separate file
// import com.example.nekit.IPStack.DNS.DNSSession // This will be imported implicitly or directly if needed


/**
 * A DNS server designed as an IPStackProtocol implementation which works with TUN interface.
 * This class aims to be thread-safe through use of coroutines and concurrent collections.
 */
open class DNSServer(
    private val serverAddress: IPAddress,
    private val serverPort: Port,
    private val pool: IPPool? = null // Fake IP pool
) : DNSResolverDelegate, IPStackProtocol {

    companion object {
        // In Swift: open static var currentServer: DNSServer?
        // TODO: Consider thread-safety (e.g., @Volatile) if accessed/modified by multiple threads without care.
        // For now, simple nullable var. Proper singleton management might be needed if only one instance is allowed.
        var currentServer: DNSServer? = null
    }

    // Using IO dispatcher for background tasks like cleanups and lookups
    private val coroutineScope = QueueFactory.getIOScope()
    private val fakeSessions: MutableMap<IPAddress, DNSSession> = ConcurrentHashMap()
    private val pendingSessions: MutableMap<UShort, DNSSession> = ConcurrentHashMap()
    private val resolvers: MutableList<DNSResolverProtocol> = mutableListOf() // Guard access if modified after start
    private val resolversMutex = Mutex()
    private val logger = LoggerFactory.getLogger(DNSServer::class.java)


    // From IPStackProtocol
    override var outputFunc: ((packets: List<ByteArray>, versions: List<Int>) -> Unit)? = null

    // Only match A record as of now, all other records should be passed directly.
    private val matchedType = listOf(DNSType.A) // Assuming DNSType.A is available

    private fun scheduleCleanupFakeIP(address: IPAddress, afterDelaySeconds: Int) {
        coroutineScope.launch {
            delay(afterDelaySeconds * 1000L)
            fakeSessions.remove(address)
            pool?.release(address) // Assumes IPPool.release is thread-safe or called from appropriate context
            logger.info("Cleaned up fake IP: {}", address)
        }
    }

    private fun scheduleCleanupPendingSession(session: DNSSession, afterDelaySeconds: Int) {
        coroutineScope.launch {
            delay(afterDelaySeconds * 1000L)
            // Only remove if it's still the same session (e.g. not replaced by a late response)
            // This check might be implicit if remove(key, value) is used, but ConcurrentHashMap.remove(key) is fine.
            pendingSessions.remove(session.requestMessage.transactionID)
            logger.info("Cleaned up pending session for TXID: {}", session.requestMessage.transactionID)
        }
    }

    private fun lookup(session: DNSSession) {
        if (!shouldMatch(session)) {
            session.matchResult = DNSSessionMatchResultType.REAL
            lookupRemotely(session)
            return
        }

        RuleManager.currentManager.matchDNS(session, RuleManagerInstance.DNSTypeDomainOrIP.DOMAIN)

        when (session.matchResult) {
            DNSSessionMatchResultType.FAKE -> {
                if (!setUpFakeIP(session)) {
                    session.matchResult = DNSSessionMatchResultType.REAL
                    lookupRemotely(session)
                    return
                }
                outputSession(session)
            }
            DNSSessionMatchResultType.REAL, DNSSessionMatchResultType.UNKNOWN -> {
                lookupRemotely(session)
            }
            else -> { // E.g., PASS, or null
                logger.error("The rule match result should not be {} after DOMAIN match.", session.matchResult)
                // Optionally, treat as REAL or drop
                 lookupRemotely(session) // Fallback to real lookup
            }
        }
    }

    private fun lookupRemotely(session: DNSSession) {
        pendingSessions[session.requestMessage.transactionID] = session
        scheduleCleanupPendingSession(session, after = Opt.DNSPendingSessionLifeTime)
        sendQueryToRemote(session)
    }

    private fun sendQueryToRemote(session: DNSSession) {
        // TODO: Consider how resolvers are managed (e.g., strategy: query all, query first, etc.)
        // For now, sending to all.
        coroutineScope.launch { // Launch in a coroutine for potential suspension in resolver.resolve
             resolversMutex.withLock { // Protect access to resolvers list if it can be modified concurrently
                for (resolver in resolvers) {
                    resolver.resolve(session)
                }
            }
        }
    }

    override fun input(packet: ByteArray, version: Int?): Boolean {
        // Basic checks (protocol, destination address, port)
        if (IPPacket.peekProtocol(packet) != TransportProtocol.UDP) return false
        // TODO: Implement IPPacket.peekDestinationAddress and Port.peekDestinationPort for efficient checks
        // For now, we might need to parse more of the IPPacket to get this info if peeking is not available.
        // This part is critical for performance.
        // Let's assume for now we parse enough of IP header to get dest addr/port.
        // A full IPPacket parse might be too slow just for filtering here.

        val destAddress = IPPacket.peekDestinationAddress(packet) // Needs proper implementation
        val destPort = IPPacket.peekDestinationPort(packet)       // Needs proper implementation

        // Fallback to full parse if peeking not good enough (less efficient)
        val tempIpPacketForCheck: IPPacket? = try { IPPacket(packet.copyOf(), version) } catch (e: Exception) { null }
        if (tempIpPacketForCheck?.destinationAddress != serverAddress ||
            (tempIpPacketForCheck?.protocolParser as? UDPProtocolParserImpl)?.destinationPort != serverPort) {
            // This check is inefficient if peek methods are not implemented.
            // For now, let's assume peeking works or this is a simplified path.
            // If peeking is not reliable, then the full parse below is the first point we'd know.
             if (IPPacket.peekDestinationAddress(packet) != serverAddress && tempIpPacketForCheck?.destinationAddress != serverAddress) return false
             if (IPPacket.peekDestinationPort(packet) != serverPort && (tempIpPacketForCheck?.protocolParser as? UDPProtocolParserImpl)?.destinationPort != serverPort) return false
        }


        val ipPacket: IPPacket = try {
            // This should parse IP and contained UDP header
            val parsed = IPPacket(packet.copyOf(), version) // Create a copy to avoid issues if `packet` is reused
            // Manually set up UDP parser if not done by IPPacket constructor
            if (parsed.transportProtocol == TransportProtocol.UDP && parsed.protocolParser == null) {
                 val udpParser = UDPProtocolParserImpl()
                 // TODO: This requires IPPacket to provide access to its payload (UDP datagram)
                 // and for UDPProtocolParser to parse it. This is complex.
                 // For now, assume DNSSession constructor will handle it if IPPacket has raw payload.
                 // Let's assume IPPacket's constructor or a method populates protocolParser correctly.
                 // For now, if it's UDP, we'll assume the payload is for DNS.
                 // A proper IPPacket parsing would give us the UDP payload.
                 // Let's assume for now IPPacket.payload is the UDP payload.
                 // For simplification, DNSSession constructor will take IPPacket and extract from its parser.
            }
            parsed
        } catch (e: Exception) {
            logger.error("Failed to parse IP packet: {}", e.message, e)
            return false
        }

        // Ensure protocol parser (UDP) is set by IPPacket constructor
        if (ipPacket.protocolParser !is UDPProtocolParserImpl) {
             logger.error("DNS Server received non-UDP packet or IPPacket parsing failed to set UDP parser.")
            return false
        }


        val session: DNSSession = try {
            DNSSession(ipPacket)
        } catch (e: Exception) {
            logger.error("Failed to create DNSSession from IP packet: {}", e.message, e)
            return false
        }

        coroutineScope.launch {
            lookup(session)
        }
        return true
    }

    override fun start() {
        // Typically, a UDP server would bind to a socket here and start listening.
        // Since this IPStackProtocol is designed for TUN, "start" might mean
        // signaling readiness or setting currentServer.
        DNSServer.currentServer = this
        logger.info("DNSServer started on {}:{}", serverAddress, serverPort)
    }

    override fun stop() {
        logger.info("DNSServer stopping...")
        coroutineScope.launch { // Use the scope for resolver operations
            resolversMutex.withLock {
                for (resolver in resolvers) {
                    resolver.stop()
                }
                resolvers.clear()
            }
        }
        // Cancel all coroutines started by this scope when server stops.
        // This will also cancel pending cleanup tasks.
        coroutineScope.cancel("DNSServer stopped")
        if (DNSServer.currentServer == this) {
            DNSServer.currentServer = null
        }
        logger.info("DNSServer stopped.")
    }

    private fun outputSession(session: DNSSession) {
        val resultType = session.matchResult ?: run {
            logger.error("outputSession called with no matchResult for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
            return
        }

        val requestUdpParser = session.requestIPPacket?.protocolParser as? UDPProtocolParser
        if (requestUdpParser?.sourcePort == null || session.requestIPPacket?.sourceAddress == null) {
            logger.error("outputSession: Missing source port/address from request for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
            return
        }

        val responseDnsMessage = DNSMessage()
        responseDnsMessage.transactionID = session.requestMessage.transactionID
        responseDnsMessage.messageType = DNSMessageType.RESPONSE
        responseDnsMessage.recursionAvailable = true // Typically true for servers acting as forwarders/resolvers
        responseDnsMessage.authoritative = false // This server is not authoritative for actual domains

        when (resultType) {
            DNSSessionMatchResultType.REAL -> {
                // Use the real response message if available
                session.realResponseMessage?.let {
                    // Copy relevant parts from real response to our responseDnsMessage shell
                    // Or, if realResponseMessage is a fully formed valid response, use its payload directly.
                    // For safety, rebuild or ensure all flags are correctly set for our server.
                    responseDnsMessage.queries = it.queries // Keep original queries
                    responseDnsMessage.answers = it.answers
                    responseDnsMessage.nameservers = it.nameservers
                    responseDnsMessage.additionals = it.additionals
                    responseDnsMessage.returnCode = it.returnCode
                } ?: run {
                    logger.error("outputSession: Real match but no real response message for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
                    responseDnsMessage.returnCode = DNSReturnCode.SERVER_FAILURE
                }
            }
            DNSSessionMatchResultType.FAKE -> {
                val fakeIp = session.fakeIP ?: run {
                    logger.error("outputSession: Fake match but no fake IP for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
                    responseDnsMessage.returnCode = DNSReturnCode.SERVER_FAILURE
                    // Fallback or send error
                    return // Cannot proceed with fake response
                }
                // Assuming queries is not empty and contains the original query name
                val queryName = session.requestMessage.queries.firstOrNull()?.name ?: "unknown.fake"
                val ttl = Opt.DNSFakeIPTTL.toUInt()
                // Create an A record for the fake IP
                DNSResource.aRecord(queryName, ttl, fakeIp)?.let {
                    responseDnsMessage.answers.add(it)
                } ?: run {
                     logger.error("Failed to create A record for fake IP {}", fakeIp)
                     responseDnsMessage.returnCode = DNSReturnCode.SERVER_FAILURE
                }
                // session.expireAt = System.currentTimeMillis() + Opt.DNSFakeIPTTL * 1000L // Already set in setUpFakeIP
            }
            else -> {
                logger.error("outputSession called with unhandled matchResult: {} for session query: {}", resultType, session.requestMessage.queries.firstOrNull()?.name)
                return
            }
        }

        val responsePayload = responseDnsMessage.build()
        if (responsePayload == null) {
            logger.error("Failed to build DNS response payload for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
            return
        }

        val responseUdpParser = UDPProtocolParserImpl()
        responseUdpParser.sourcePort = serverPort
        responseUdpParser.destinationPort = requestUdpParser.sourcePort
        responseUdpParser.payload = responsePayload

        val responseIpPacket = IPPacket(ByteArray(0)) // Dummy data, buildPacket will create actual
        responseIpPacket.sourceAddress = serverAddress
        responseIpPacket.destinationAddress = session.requestIPPacket!!.sourceAddress
        responseIpPacket.protocolParser = responseUdpParser
        responseIpPacket.transportProtocol = TransportProtocol.UDP
        responseIpPacket.buildPacket() // This needs to fill packetData

        if (responseIpPacket.packetData.isNotEmpty()) {
            // The version parameter for outputFunc is likely AF_INET or AF_INET6
            val version = if (responseIpPacket.destinationAddress?.isIPv4 == true) AddressFamily.AF_INET else AddressFamily.AF_INET6
            outputFunc?.invoke(listOf(responseIpPacket.packetData), listOf(version))
        } else {
            logger.error("Built IP packet for DNS response is empty for session query: {}", session.requestMessage.queries.firstOrNull()?.name)
        }
    }

    private fun shouldMatch(session: DNSSession): Boolean {
        // Only A records are considered for fake IP generation in original logic
        return session.requestMessage.type?.let { matchedType.contains(it) } ?: false
    }

    fun isFakeIP(ipAddress: IPAddress): Boolean {
        return pool?.contains(ipAddress) ?: false
    }

    // Original used QueueFactory.executeOnQueueSynchronizedly
    // Replaced with direct access to ConcurrentHashMap or Mutex if needed.
    // fakeSessions is ConcurrentHashMap, so direct access is okay for get.
    fun lookupFakeIP(address: IPAddress): DNSSession? {
        return fakeSessions[address]
    }

    /**
     * Adds a new DNS resolver to this DNS server.
     */
    suspend fun registerResolver(resolver: DNSResolverProtocol) { // Made suspend if resolver.delegate assignment needs specific context
        resolver.delegate = this // This DNSServer instance acts as a delegate for upstream resolvers
        resolversMutex.withLock { // Protect mutable list
            resolvers.add(resolver)
        }
    }

    private fun setUpFakeIP(session: DNSSession): Boolean {
        val fakeIP = pool?.fetchIP() ?: run {
            logger.debug("Failed to get a fake IP from pool for session query: {}", session.requestMessage.queries.firstOrNull()?.name) // VERBOSE -> debug
            return false
        }
        session.fakeIP = fakeIP
        fakeSessions[fakeIP] = session // ConcurrentHashMap handles thread safety
        session.expireAt = System.currentTimeMillis() + Opt.DNSFakeIPTTL * 1000L
        // Schedule cleanup for the fake IP mapping
        scheduleCleanupFakeIP(fakeIP, afterDelaySeconds = Opt.DNSFakeIPTTL * 2)
        logger.info("Setup fake IP {} for session (query: {})", fakeIP, session.requestMessage.queries.firstOrNull()?.name)
        return true
    }

    // Implementation of DNSResolverDelegate
    override fun didReceive(rawResponse: ByteArray) {
        val message: DNSMessage = try {
            DNSMessage(rawResponse)
        } catch (e: Exception) {
            logger.error("Failed to parse response from remote DNS server: {}", e.message, e)
            return
        }

        coroutineScope.launch {
            val session = pendingSessions.remove(message.transactionID)
            if (session == null) {
                logger.debug("Received DNS response with TXID {} but no matching pending session found.", message.transactionID) // VERBOSE -> debug
                return@launch
            }

            session.realResponseMessage = message
            session.realIP = message.resolvedIPv4Address // Assuming A record for now

            // If the original match result was not definitively FAKE or REAL (e.g., UNKNOWN),
            // re-evaluate rules with the IP information.
            if (session.matchResult != DNSSessionMatchResultType.FAKE && session.matchResult != DNSSessionMatchResultType.REAL) {
                RuleManager.currentManager.matchDNS(session, RuleManagerInstance.DNSTypeDomainOrIP.IP)
            }

            when (session.matchResult) {
                DNSSessionMatchResultType.FAKE -> {
                    if (!setUpFakeIP(session)) {
                        // Failed to set up fake IP (e.g., pool empty), so fallback to sending real response
                        logger.warn("Could not set up fake IP for {}, falling back to REAL response.", session.requestMessage.queries.firstOrNull()?.name)
                        session.matchResult = DNSSessionMatchResultType.REAL
                    }
                    outputSession(session)
                }
                DNSSessionMatchResultType.REAL -> {
                    outputSession(session)
                }
                else -> {
                    logger.error("The rule match result {} is not supported after IP match for TXID {}.", session.matchResult, session.requestMessage.transactionID)
                    // Optionally, could default to outputting the REAL response if available
                    // if (session.realResponseMessage != null) outputSession(session)
                }
            }
        }
    }
}
