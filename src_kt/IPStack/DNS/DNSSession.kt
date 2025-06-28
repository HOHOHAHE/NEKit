package IPStack.DNS

import Messages.ConnectSession
import org.slf4j.LoggerFactory
import IPStack.Packet.IPPacket
import IPStack.Packet.UDPProtocolParser // For parsing UDP payload
import Utils.IPAddress
import Rule.Rule // For matchedRule
import Rule.DNSSessionMatchResultType // For matchResult
import IPStack.DNS.DNSEnums.DNSType
import IPStack.DNS.DNSEnums.DNSMessage
import IPStack.DNS.DNSEnums.DNSQuery

/**
 * Represents a DNS session, encapsulating the request, response, and associated metadata.
 * This class is designed to be passed around and updated as the DNS resolution process
 * progresses.
 */
open class DNSSession(
    open var requestMessage: DNSMessage,
    open var requestIPPacket: IPPacket? = null
) {
    open var matchResult: DNSSessionMatchResultType? = null
    open var fakeIP: IPAddress? = null
    open var realResponseMessage: DNSMessage? = null
    open var realIP: IPAddress? = null
    open var expireAt: Long = 0 // Swift Date -> Kotlin Long (epoch millis)

    // Properties for rule matching and tracking
    open var indexToMatch: Int = 0 // Used by RuleManager to track where to start matching next
    open var matchedRule: Rule? = null // The rule that matched this session

    private val logger = LoggerFactory.getLogger(DNSSession::class.java)

    /**
     * Secondary constructor to create a DNSSession from an incoming IPPacket.
     * Assumes the packet contains a UDP payload that is a DNS message.
     */
    constructor(packet: IPPacket) : this(
        requestMessage = DNSMessage(), // Dummy, will be replaced by parsed message
        requestIPPacket = packet
    ) {
        val udpParser = packet.protocolParser as? UDPProtocolParserImpl
        if (udpParser?.payload != null) {
            try {
                this.requestMessage = DNSMessage(udpParser.payload!!) // Parse the actual DNS message
            } catch (e: Exception) {
                logger.error("Error parsing DNSMessage from UDP payload: {}", e.message, e)
                throw IllegalArgumentException("Invalid DNS data in UDP payload", e)
            }
        } else {
            logger.error("UDP payload missing for DNSSession from IPPacket, or protocol parser not UDP.")
            throw IllegalArgumentException("Missing UDP payload or incorrect protocol parser for DNS session.")
        }
    }

    /**
     * Constructor for creating an outgoing DNS query session.
     */
    constructor(queryName: String, queryType: DNSType) : this(DNSMessage().apply {
        this.recursionDesired = true
        this.queries.add(DNSQuery(queryName, queryType))
    })

    /**
     * Lazily built request payload from the DNSMessage.
     */
    val builtRequestPayload: ByteArray? by lazy { requestMessage.build() }
}