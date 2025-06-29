package IPStack.Packet
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Assuming Port.kt and Checksum.kt (from Utils) are available.
// Assuming IPAddress.kt (for pseudo header parts, though not directly here) is available.

/**
 * Interface for transport layer protocol parsers (e.g., UDP, TCP).
 * A parser is responsible for parsing an existing transport segment from an IP packet's payload
 * and for building a new transport segment.
 */
interface TransportProtocolParser {
    /**
     * The raw byte array of the *entire IP packet*.
     * The parser reads from/writes to this array at `transportHeaderOffset`.
     */
    var ipPacketRawData: ByteArray

    /**
     * The offset (in bytes) where the transport layer header begins within `ipPacketRawData`.
     */
    var transportHeaderOffset: Int

    /**
     * The total length of the transport segment (header + payload).
     * For UDP, this is UDP header length (8) + UDP payload length.
     * For TCP, this is TCP header length (min 20) + TCP payload length.
     */
    val segmentLength: Int // Renamed from bytesLength for clarity

    /**
     * The actual application data (payload) of the transport segment.
     */
    var payloadData: ByteArray?

    /**
     * Builds the transport layer segment (header and payload) into the `ipPacketRawData`
     * starting at `transportHeaderOffset`.
     * This includes calculating and writing the transport layer checksum.
     *
     * @param pseudoHeaderChecksum The pseudo-header checksum calculated from IP header fields,
     *                             needed for TCP/UDP checksum computation.
     */
    fun buildSegment(pseudoHeaderChecksum: UInt)

    /**
     * Parses the transport layer segment from `ipPacketRawData` starting at `transportHeaderOffset`.
     * Populates fields like sourcePort, destinationPort, payloadData, etc.
     * This method should be called after `ipPacketRawData` and `transportHeaderOffset` are set.
     *
     * @throws IllegalArgumentException if parsing fails due to malformed data or insufficient length.
     */
    @Throws(IllegalArgumentException::class)
    fun parse()
}


/**
 * Parser for UDP (User Datagram Protocol) segments.
 */
import com.example.nekit.Utils.Port
import com.example.nekit.Utils.Checksum

class UDPProtocolParser : TransportProtocolParser {
    override lateinit var ipPacketRawData: ByteArray
    override var transportHeaderOffset: Int = 0

    var sourcePort: Port? = null
    var destinationPort: Port? = null
    private var udpLength: UShort = 0u // Length of UDP header + UDP data, from UDP header
    private var udpChecksum: UShort = 0u // From UDP header

    override var payloadData: ByteArray? = null

    override val segmentLength: Int
        get() = (payloadData?.size ?: 0) + UDP_HEADER_LENGTH

    companion object {
        const val UDP_HEADER_LENGTH = 8
    }

    /**
     * Default constructor. Fields should be set before calling buildSegment,
     * or ipPacketRawData/transportHeaderOffset set before calling parse.
     */
    constructor()

    /**
     * Convenience constructor for parsing.
     * @param packetData The full IP packet data.
     * @param offset The offset where the UDP header starts.
     * @throws IllegalArgumentException if parsing fails.
     */
    constructor(packetData: ByteArray, offset: Int) {
        this.ipPacketRawData = packetData
        this.transportHeaderOffset = offset
        parse()
    }

    override fun parse() {
        if (!::ipPacketRawData.isInitialized || ipPacketRawData.size < transportHeaderOffset + UDP_HEADER_LENGTH) {
            throw IllegalArgumentException("UDP packet data too short for header. Needed: ${transportHeaderOffset + UDP_HEADER_LENGTH}, Got: ${ipPacketRawData.size}")
        }

        val buffer = ByteBuffer.wrap(ipPacketRawData, transportHeaderOffset, ipPacketRawData.size - transportHeaderOffset)
            .order(ByteOrder.BIG_ENDIAN)

        sourcePort = Port(buffer.short.toUShort()) // Port constructor expects network order value
        destinationPort = Port(buffer.short.toUShort())
        udpLength = buffer.short.toUShort()
        udpChecksum = buffer.short.toUShort()

        val payloadLen = udpLength.toInt() - UDP_HEADER_LENGTH
        if (payloadLen < 0) {
            throw IllegalArgumentException("Invalid UDP length in header: $udpLength")
        }
        if (buffer.remaining() < payloadLen) {
            System.err.println("WARN: UDP payload length in header ($payloadLen bytes) is greater than remaining bytes in packet (${buffer.remaining()}). Truncating.")
            // This can happen with malformed packets or if ipPacketRawData was already truncated.
            // payloadData = ByteArray(buffer.remaining()) // Take what's left
            // For stricter parsing, one might throw an exception here.
            // The original Swift code `subdata(in: offset+8..<packetData.count)` would take all remaining.
            // Let's match that, but it means udpLength might be misleading if packet was truncated.
            payloadData = ByteArray(ipPacketRawData.size - (transportHeaderOffset + UDP_HEADER_LENGTH))
             ByteBuffer.wrap(ipPacketRawData, transportHeaderOffset + UDP_HEADER_LENGTH, payloadData!!.size)
                .get(payloadData!!)

        } else if (payloadLen > 0) {
            payloadData = ByteArray(payloadLen)
            buffer.get(payloadData!!)
        } else {
            payloadData = ByteArray(0)
        }
    }

    override fun buildSegment(pseudoHeaderChecksum: UInt) {
        val currentPayload = payloadData ?: ByteArray(0)
        this.udpLength = (UDP_HEADER_LENGTH + currentPayload.size).toUShort()

        // Ensure ipPacketRawData is large enough (caller, e.g. IPPacket.buildPacket, should ensure this)
        // Or, this method could return a new ByteArray for its segment.
        // Given the protocol, it modifies ipPacketRawData.
        if (!::ipPacketRawData.isInitialized || ipPacketRawData.size < transportHeaderOffset + this.udpLength.toInt()) {
            // This situation is tricky. If ipPacketRawData is too small, we can't write.
            // This implies that IPPacket.buildPacket should have allocated ipPacketRawData correctly
            // before calling this.
            // For now, assume it's correctly sized.
            throw IllegalStateException("ipPacketRawData not initialized or too small for UDP segment.")
        }

        val buffer = ByteBuffer.wrap(ipPacketRawData, transportHeaderOffset, this.udpLength.toInt())
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putShort(sourcePort?.networkOrderValue?.toShort() ?: 0)
        buffer.putShort(destinationPort?.networkOrderValue?.toShort() ?: 0)
        buffer.putShort(this.udpLength.toShort())
        buffer.putShort(0) // Checksum placeholder before calculation
        buffer.put(currentPayload)

        // Calculate UDP Checksum
        // Checksum is calculated over: pseudo-header + UDP header + UDP payload.
        // The Checksum utility needs the data segment to checksum.
        // We need to construct this segment.

        // 1. Create a temporary buffer for checksum calculation
        val pseudoHeaderSize = 12 // Typical for IPv4: srcIP(4) + dstIP(4) + zeros(1) + proto(1) + udpLen(2)
                                  // The pseudoHeaderChecksum param already contains the sum of these parts *except* udpLen.
                                  // The standard way: sum of pseudo header fields + UDP segment (with checksum field zeroed).

        // Let's use a simpler interpretation of Checksum.computeChecksum if it can take a pre-summed pseudo header.
        // The Swift version `Checksum.computeChecksum(packetData, from: offset, to: nil, withPseudoHeaderChecksum: pseudoHeaderChecksum)`
        // implies that `packetData` from `offset` onwards (the UDP segment) is checksummed, and `pseudoHeaderChecksum` is added.

        // Make sure the checksum field within the buffer is zeroed out for calculation
        // buffer.putShort(6, 0.toShort()) // Position 6 within UDP header is checksum field

        // The Checksum.computeChecksum in Swift took the *full IP packetData* and an *offset*.
        // This is unusual. UDP checksum is over pseudo-header + UDP segment.
        // For now, assume Checksum.computeChecksum can correctly handle this if given
        // the UDP segment (header+payload) and the pseudoHeaderChecksum.
        // This part needs careful alignment with how Checksum.computeChecksum Kotlin version works.
        // A common way: Sum(pseudo-header fields) + Sum(UDP header + UDP data with checksum field as 0).
        // The provided `pseudoHeaderChecksum` from Swift's IPPacket.computePseudoHeaderChecksum was:
        // srcIP + dstIP + protocol_byte_padded + transport_length_swapped.
        // This is a common way to sum the pseudo header.

        // Let's assume Checksum.kt's computeChecksum can take this precomputed sum and the UDP segment.
        // The UDP segment for checksumming starts at `transportHeaderOffset` and has length `this.udpLength`.
        val checksumValue = Checksum.computeChecksum(
            data = ipPacketRawData,
            from = transportHeaderOffset,
            to = transportHeaderOffset + this.udpLength.toInt(),
            withPseudoHeaderChecksum = pseudoHeaderChecksum, // This is the sum of pseudo header fields
            zeroOutOriginalChecksumFieldAt = transportHeaderOffset + 6 // Offset of checksum field in UDP header
        ).toUShort()

        // Write the actual checksum
        // We need to re-get the buffer or ensure its position is correct if not already done.
        // The buffer used for writing earlier is already wrapping ipPacketRawData.
        ByteBuffer.wrap(ipPacketRawData).order(ByteOrder.BIG_ENDIAN).putShort(transportHeaderOffset + 6, checksumValue.toShort())
    }
}

// Extend Checksum.kt to support the zeroOutOriginalChecksumFieldAt parameter if it doesn't exist
// This is a conceptual extension. Actual Checksum.kt would need modification or this added there.
/*
object Checksum {
    fun computeChecksum(
        data: ByteArray,
        from: Int = 0,
        to: Int? = null,
        withPseudoHeaderChecksum: UInt = 0u,
        zeroOutOriginalChecksumFieldAt: Int? = null // Byte offset within `data`
    ): UShort {
        val actualTo = to ?: data.size
        var sum = withPseudoHeaderChecksum

        // If a checksum field needs to be zeroed out for calculation
        var originalChecksumWord: UShort? = null
        if (zeroOutOriginalChecksumFieldAt != null &&
            zeroOutOriginalChecksumFieldAt >= from &&
            zeroOutOriginalChecksumFieldAt + 1 < actualTo) {
            originalChecksumWord = ByteBuffer.wrap(data, zeroOutOriginalChecksumFieldAt, 2)
                                    .order(ByteOrder.BIG_ENDIAN).short.toUShort()
        }

        for (i in from until actualTo step 2) {
            var word: UShort
            if (zeroOutOriginalChecksumFieldAt != null && i == zeroOutOriginalChecksumFieldAt) {
                word = 0u // Treat checksum field as zero
            } else if (i + 1 < actualTo) {
                word = ByteBuffer.wrap(data, i, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            } else {
                word = (data[i].toUByte().toUInt() shl 8).toUShort() // Odd byte, pad with 0
            }
            sum += word.toUInt()
            if (sum > 0xFFFFu) { // Fold carry
                sum = (sum and 0xFFFFu) + (sum shr 16)
            }
        }
         while (sum shr 16 > 0u) { // Final fold
            sum = (sum and 0xFFFFu) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFu).toUShort()
    }
}
*/
