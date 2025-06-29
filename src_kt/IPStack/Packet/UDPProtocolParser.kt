package com.example.nekit.IPStack.Packet

import com.example.nekit.Utils.Port
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.slf4j.LoggerFactory

/**
 * Represents a UDP protocol parser.
 */
open class UDPProtocolParser(override val pseudoHeaderChecksum: UInt = 0u) : TransportProtocolParser {
    private val logger = LoggerFactory.getLogger(UDPProtocolParser::class.java)

    override var sourcePort: Port? = null
    override var destinationPort: Port? = null
    override var payloadData: ByteArray? = null
    override var headerLength: Int = 8 // UDP header is 8 bytes

    // For parsing
    constructor(data: ByteArray, pseudoHeaderChecksum: UInt = 0u) : this(pseudoHeaderChecksum) {
        if (data.size < 8) {
            logger.warn("UDP data too short for header: ${data.size} bytes.")
            return
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        sourcePort = Port(buffer.short.toInt() and 0xFFFF)
        destinationPort = Port(buffer.short.toInt() and 0xFFFF)
        headerLength = buffer.short.toInt() // This is the total length of UDP header + data
        // Checksum is read but not stored as a property, as it's usually re-calculated
        buffer.short

        if (data.size > 8) {
            payloadData = data.copyOfRange(8, data.size)
        }
    }

    override fun buildPacket(): ByteArray {
        val buffer = ByteBuffer.allocate(headerLength + (payloadData?.size ?: 0)).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(sourcePort?.hostOrderValue?.toShort() ?: 0)
        buffer.putShort(destinationPort?.hostOrderValue?.toShort() ?: 0)
        buffer.putShort((headerLength + (payloadData?.size ?: 0)).toShort()) // Length of UDP header + data
        buffer.putShort(0) // Checksum placeholder
        payloadData?.let { buffer.put(it) }
        return buffer.array()
    }

    override fun updateChecksum(checksum: UShort) {
        // In a real implementation, this would update the checksum field in the byte array.
        // For now, it's a no-op as buildPacket always puts 0.
        logger.warn("UDPProtocolParser.updateChecksum is a no-op in this placeholder implementation.")
    }
}
