package com.example.nekit.IPStack.Packet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random // For default identification

import org.slf4j.LoggerFactory

// Assuming utilities like IPAddress, Port, BinaryDataScanner, Checksum are available
// Assuming DNSEnums (for TransportProtocol if used directly, though it has its own here)

// --- Enums (can be in a common PacketEnums.kt or similar) ---
enum class IPVersion(val value: UByte) {
    IPv4(4u),
    IPv6(6u);

    companion object {
        fun fromByte(byte: UByte): IPVersion? = entries.find { it.value == byte }
    }
}

enum class TransportProtocol(val rawValue: UByte) {
    ICMP(1u),
    TCP(6u),
    UDP(17u),
    UNKNOWN(0u); // For cases where it's not one of the above or not determined

    companion object {
        fun fromByte(byte: UByte): TransportProtocol? = entries.find { it.rawValue == byte }
    }
}

// --- Protocol for Transport Layer Parsers ---
interface TransportProtocolParser {
    val protocol: TransportProtocol
    val headerLength: Int
    var payload: ByteArray? // The actual data after the transport header
    val pseudoHeaderChecksum: UInt // Calculated by IPPacket for TCP/UDP checksum

    fun parse(headerData: ByteBuffer, pseudoHeaderChecksum: UInt)
    fun build(payloadData: ByteArray): ByteArray // Returns the transport header + payload
    // Original Swift had `bytesLength` for protocol parser, meaning header + payload length it manages
    val totalLength: Int // Total length of transport header + its payload
}

// Placeholder for UDPProtocolParser (refine based on actual UDPProtocolParser.swift)
class UDPProtocolParser(override val pseudoHeaderChecksum: UInt = 0u) : TransportProtocolParser {
    override val protocol = TransportProtocol.UDP
    override val headerLength = 8 // UDP Header is 8 bytes
    var sourcePort: Port? = null
    var destinationPort: Port? = null
    var udpPayloadLength: UShort = 0u // Length of UDP header + data
    var checksum: UShort = 0u
    override var payload: ByteArray? = null
    override val totalLength: Int get() = headerLength + (payload?.size ?: 0)


    override fun parse(headerData: ByteBuffer, pseudoHeaderChecksum: UInt) {
        // headerData is positioned at the start of UDP header
        sourcePort = Port(headerData.short.toUShort()) // Network order from Port constructor
        destinationPort = Port(headerData.short.toUShort())
        udpPayloadLength = headerData.short.toUShort()
        checksum = headerData.short.toUShort()
        val dataLength = udpPayloadLength.toInt() - headerLength
        if (dataLength > 0 && headerData.remaining() >= dataLength) {
            payload = ByteArray(dataLength)
            headerData.get(payload!!)
        } else if (dataLength < 0) {
            logger.error("UDP Parse Error: Invalid UDP payload length {}", udpPayloadLength)
        }
        // TODO: Validate checksum
    }

    override fun build(payloadData: ByteArray): ByteArray {
        this.payload = payloadData
        this.udpPayloadLength = (headerLength + payloadData.size).toUShort()
        val buffer = ByteBuffer.allocate(this.udpPayloadLength.toInt()).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(sourcePort?.networkOrderValue?.toShort() ?: 0)
        buffer.putShort(destinationPort?.networkOrderValue?.toShort() ?: 0)
        buffer.putShort(this.udpPayloadLength.toShort())
        buffer.putShort(0) // Checksum placeholder

        buffer.put(payloadData)

        // Calculate checksum
        // TODO: Implement actual UDP checksum calculation using pseudoHeaderChecksum + UDP packet
        // val finalChecksum = calculateUdpChecksum(pseudoHeaderChecksum, buffer.array())
        // buffer.putShort(6, finalChecksum.toShort())
        return buffer.array()
    }
}


/**
 * Represents an IP Packet, focused on IPv4.
 * Allows parsing from and building to ByteArray.
 */
open class IPPacket {
    private val logger = LoggerFactory.getLogger(IPPacket::class.java)
    open var version: IPVersion = IPVersion.IPv4
    open var ipHeaderLength: Int = 20 // In bytes, min for IPv4
    open var tos: UByte = 0u // Type of Service
    open var totalLength: UShort = 0u // Will be set by packetData or during build
        private set(value) { field = value } // Make setter private if calculated

    open var identification: UShort = Random.nextInt(0, UShort.MAX_VALUE.toInt() + 1).toUShort()
    open var flagsAndFragmentOffset: UShort = 0u // Includes 3 flags bits and 13 offset bits
        get() = ( (flags.toInt() shl 13) or (fragmentOffset.toInt() and 0x1FFF) ).toUShort()
        set(value) {
            field = value
            flags = (value.toInt() shr 13) and 0x07
            fragmentOffset = (value.toInt() and 0x1FFF).toUShort()
        }
    // Individual flags (exposed for convenience, derived from flagsAndFragmentOffset)
    // For IPv4: Bit 0: Reserved (must be 0), Bit 1: Don't Fragment (DF), Bit 2: More Fragments (MF)
    var flags: Int = 0 // 3 bits
    var fragmentOffset: UShort = 0u // 13 bits

    open var ttl: UByte = 64u
    open var transportProtocol: TransportProtocol = TransportProtocol.UNKNOWN
    open var headerChecksum: UShort = 0u

    open var sourceAddress: IPAddress? = null
    open var destinationAddress: IPAddress? = null

    open var protocolParser: TransportProtocolParser? = null // e.g., UDPParser, TCPParser

    // Raw packet data, including header and payload.
    // This is the source of truth after parsing, or the result after building.
    var packetData: ByteArray? = null


    /**
     * Default constructor for building a new packet.
     * Fields should be set before calling buildPacket().
     */
    constructor()

    /**
     * Constructor for parsing an existing IP packet from ByteArray.
     * Currently supports IPv4 only.
     */
    constructor(data: ByteArray) {
        this.packetData = data // Keep reference to original data for now
        if (data.size < 20) { // Min IPv4 header size
            throw IllegalArgumentException("Packet data too short for IPv4 header (${data.size} bytes).")
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val versionAndIhlByte = buffer.get().toUByte()
        val parsedVersion = IPVersion.fromByte(versionAndIhlByte shr 4)
            ?: throw IllegalArgumentException("Unknown IP version nibble: ${versionAndIhlByte shr 4}")
        if (parsedVersion != IPVersion.IPv4) {
            throw IllegalArgumentException("Only IPv4 parsing is supported. Got: $parsedVersion")
        }
        this.version = parsedVersion
        this.ipHeaderLength = (versionAndIhlByte and 0x0Fu).toInt() * 4
        if (this.ipHeaderLength < 20 || data.size < this.ipHeaderLength) {
            throw IllegalArgumentException("Invalid IP header length ($ipHeaderLength bytes) or packet too short.")
        }

        this.tos = buffer.get().toUByte()
        this.totalLength = buffer.short.toUShort()
        if (this.totalLength.toInt() != data.size) {
            logger.warn("IP packet total length in header ({}) does not match actual data length ({}). Using actual data length.", this.totalLength, data.size)
            // This warning is because NEPacketTunnelFlow might provide full data, not just up to totalLength.
            // For consistency, totalLength should reflect the received data size if it's what we process.
            // However, if building, totalLength should be accurate for header.
            // For parsing, it's a validation.
        }

        this.identification = buffer.short.toUShort()
        this.flagsAndFragmentOffset = buffer.short.toUShort() // This also sets individual flags and fragmentOffset
        this.ttl = buffer.get().toUByte()

        val protoByte = buffer.get().toUByte()
        this.transportProtocol = TransportProtocol.fromByte(protoByte)
            ?: run {
                logger.warn("Unknown transport protocol byte: {}", protoByte)
                TransportProtocol.UNKNOWN
            }

        this.headerChecksum = buffer.short.toUShort() // Read existing checksum

        val srcIpBytes = ByteArray(4)
        buffer.get(srcIpBytes)
        this.sourceAddress = IPAddress.fromBytes(srcIpBytes, IPAddress.Family.IPv4)

        val dstIpBytes = ByteArray(4)
        buffer.get(dstIpBytes)
        this.destinationAddress = IPAddress.fromBytes(dstIpBytes, IPAddress.Family.IPv4)

        // TODO: Handle IP options if ipHeaderLength > 20

        if (this.totalLength.toInt() > this.ipHeaderLength && buffer.hasRemaining()) {
            val payloadOffset = this.ipHeaderLength
            val payloadLength = this.totalLength.toInt() - this.ipHeaderLength
            if (buffer.remaining() < payloadLength) {
                 logger.warn("Not enough bytes in buffer for declared payload. Buffer remaining: {}, payloadLength: {}", buffer.remaining(), payloadLength)
                 // Potentially throw error or truncate
            }

            val pseudoHeaderChecksumVal = computePseudoHeaderChecksum(data.size - ipHeaderLength) // Pass transport payload length

            when (this.transportProtocol) {
                TransportProtocol.UDP -> {
                    val udpParser = UDPProtocolParser(pseudoHeaderChecksumVal)
                    udpParser.parse(buffer, pseudoHeaderChecksumVal) // buffer is now positioned at start of UDP
                    this.protocolParser = udpParser
                }
                TransportProtocol.TCP -> {
                    // TODO: val tcpParser = TCPProtocolParserImpl(); tcpParser.parse(buffer, pseudoHeaderChecksumVal); this.protocolParser = tcpParser
                    logger.warn("TCP parsing not yet implemented.")
                }
                TransportProtocol.ICMP -> {
                     // TODO: val icmpParser = ICMPProtocolParserImpl(); icmpParser.parse(buffer); this.protocolParser = icmpParser
                    logger.warn("ICMP parsing not yet implemented.")
                }
                else -> {
                    logger.warn("No parser for protocol: {}", this.transportProtocol)
                    // Store raw payload if needed
                    val rawPayload = ByteArray(payloadLength)
                    buffer.get(rawPayload)
                    // this.rawPayload = rawPayload // If IPPacket needs to store unknown L4 payload
                }
            }
        } else if (this.totalLength.toInt() < this.ipHeaderLength) {
             logger.warn("IP total length ({}) less than header length ({}).", this.totalLength, this.ipHeaderLength)
        }
    }

    private fun computePseudoHeaderChecksum(transportPayloadLength: Int): UInt {
        var sum: UInt = 0u
        // Source IP Address
        sourceAddress?.addressBytes?.let {
            sum += ( (it[0].toUInt() and 0xFFu shl 8) or (it[1].toUInt() and 0xFFu) )
            sum += ( (it[2].toUInt() and 0xFFu shl 8) or (it[3].toUInt() and 0xFFu) )
        }
        // Destination IP Address
        destinationAddress?.addressBytes?.let {
            sum += ( (it[0].toUInt() and 0xFFu shl 8) or (it[1].toUInt() and 0xFFu) )
            sum += ( (it[2].toUInt() and 0xFFu shl 8) or (it[3].toUInt() and 0xFFu) )
        }
        // Protocol
        sum += transportProtocol.rawValue.toUInt() // Zero-padded to 16 bits, protocol is in low byte
        // TCP/UDP Length (Transport header + Transport payload)
        sum += transportPayloadLength.toUInt()

        // Fold to 16 bits
        while (sum shr 16 > 0u) {
            sum = (sum and 0xFFFFu) + (sum shr 16)
        }
        return sum
    }


    /**
     * Builds the packetData ByteArray from the current properties.
     * Calculates IP header checksum and delegates to transport protocol parser for its segment.
     */
    open fun buildPacket() {
        val currentProtocolParser = protocolParser ?: throw IllegalStateException("Protocol parser not set.")
        val transportSegment = currentProtocolParser.build(currentProtocolParser.payload ?: ByteArray(0))

        this.ipHeaderLength = 20 // Assuming no IP options for now
        this.totalLength = (this.ipHeaderLength + transportSegment.size).toUShort()

        val buffer = ByteBuffer.allocate(this.totalLength.toInt()).order(ByteOrder.BIG_ENDIAN)

        buffer.put(((version.value.toInt() shl 4) or (ipHeaderLength / 4)).toByte())
        buffer.put(tos.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(identification.toShort())
        buffer.putShort(flagsAndFragmentOffset.toShort())
        buffer.put(ttl.toByte())
        buffer.put(transportProtocol.rawValue.toByte())
        buffer.putShort(0) // Checksum placeholder

        buffer.put(sourceAddress?.addressBytes ?: ByteArray(4))
        buffer.put(destinationAddress?.addressBytes ?: ByteArray(4))

        // TODO: Handle IP Options if ipHeaderLength > 20

        buffer.put(transportSegment)

        // Calculate and set IP header checksum
        val headerBytesForChecksum = buffer.array().copyOfRange(0, ipHeaderLength)
        this.headerChecksum = Checksum.computeChecksum(headerBytesForChecksum).toUShort() // Assuming Checksum.kt from Utils
        buffer.putShort(10, this.headerChecksum.toShort())

        this.packetData = buffer.array()
    }


    companion object {
        // Peek methods using ByteBuffer for safer access
        fun peekIPVersion(data: ByteArray): IPVersion? {
            if (data.size < 1) return null
            return IPVersion.fromByte(data[0].toUByte() shr 4)
        }

        fun peekProtocol(data: ByteArray): TransportProtocol? {
            if (data.size < 10) return null // Protocol is at offset 9
            return TransportProtocol.fromByte(data[9].toUByte())
        }

        fun peekSourceAddress(data: ByteArray): IPAddress? {
            if (data.size < 16) return null // Source IP ends at offset 15
            val ipBytes = data.copyOfRange(12, 16)
            return IPAddress.fromBytes(ipBytes, IPAddress.Family.IPv4)
        }

        fun peekDestinationAddress(data: ByteArray): IPAddress? {
            if (data.size < 20) return null // Dest IP ends at offset 19
            val ipBytes = data.copyOfRange(16, 20)
            return IPAddress.fromBytes(ipBytes, IPAddress.Family.IPv4)
        }

        fun peekSourcePort(data: ByteArray): Port? {
            val proto = peekProtocol(data)
            if (proto != TransportProtocol.TCP && proto != TransportProtocol.UDP) return null
            if (data.size < 1) return null
            val ihl = (data[0].toUByte() and 0x0Fu).toInt() * 4
            if (ihl < 20 || data.size < ihl + 2) return null // Source port ends at ihl + 1
            return Port(ByteBuffer.wrap(data, ihl, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort())
        }

        fun peekDestinationPort(data: ByteArray): Port? {
            val proto = peekProtocol(data)
            if (proto != TransportProtocol.TCP && proto != TransportProtocol.UDP) return null
            if (data.size < 1) return null
            val ihl = (data[0].toUByte() and 0x0Fu).toInt() * 4
            if (ihl < 20 || data.size < ihl + 4) return null // Dest port ends at ihl + 3
            return Port(ByteBuffer.wrap(data, ihl + 2, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort())
        }
    }
}
