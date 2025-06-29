package com.example.nekit.IPStack.Packet

import com.example.nekit.Utils.Port
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.slf4j.LoggerFactory

/**
 * Represents a parser for a transport layer protocol (e.g., TCP, UDP).
 * This interface defines common properties and methods for transport protocol headers.
 */
interface TransportProtocolParser {
    val sourcePort: Port?
    val destinationPort: Port?
    val payloadData: ByteArray?
    val headerLength: Int
    val pseudoHeaderChecksum: UInt

    /**
     * Builds the byte representation of the protocol header and payload.
     * @return The byte array of the protocol segment.
     */
    fun buildPacket(): ByteArray

    /**
     * Updates the checksum of the protocol header.
     * @param checksum The new checksum value.
     */
    fun updateChecksum(checksum: UShort)
}
