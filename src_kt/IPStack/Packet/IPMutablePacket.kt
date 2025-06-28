import java.nio.ByteBuffer
import java.nio.ByteOrder

// Assuming IPAddress.kt from Utils is available and handles IPv4.

import IPStack.Packet.IPVersion
import IPStack.Packet.TransportProtocol

// Enum for type of change affecting checksum
internal enum class ChecksumChangeType {
    ADDRESS_PAIR // Indicates a pair of 16-bit words (like an IP address) changed
    // PORT_PAIR, // If port checksumming was also handled here (TCP/UDP checksums are different)
    // OTHER_WORD // For generic 16-bit field changes
}


/**
 * Represents a mutable IPv4 packet.
 * Allows modification of source/destination IP addresses and updates the IP header checksum.
 *
 * Note: This implementation focuses on IPv4 as per the original Swift code.
 * The underlying ByteArray is modified in-place via ByteBuffer.
 */
class IPMutablePacket(private val rawPacketBytes: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(rawPacketBytes).order(ByteOrder.BIG_ENDIAN)

    val version: IPVersion
    val protocol: TransportProtocol
    val ipHeaderLength: Int // in bytes

    var sourceAddress: IPAddress
        get() {
            val addrBytes = ByteArray(4)
            buffer.position(12) // IPv4 Source Address offset
            buffer.get(addrBytes)
            return IPAddress.fromBytes(addrBytes, IPAddress.Family.IPv4)
                ?: throw IllegalStateException("Failed to parse source IPv4 address from packet bytes.")
        }
        set(newAddress) {
            if (!newAddress.isIPv4) throw IllegalArgumentException("New source address must be IPv4.")
            val oldAddressBytes = ByteArray(4)
            buffer.position(12)
            buffer.get(oldAddressBytes) // Read old address bytes for checksum adjustment

            // Write new address
            buffer.position(12)
            buffer.put(newAddress.addressBytes)

            // Update checksum: IP addresses are two 16-bit words
            updateChecksumForFieldChange(oldAddressBytes, newAddress.addressBytes, 10)
        }

    var destinationAddress: IPAddress
        get() {
            val addrBytes = ByteArray(4)
            buffer.position(16) // IPv4 Destination Address offset
            buffer.get(addrBytes)
            return IPAddress.fromBytes(addrBytes, IPAddress.Family.IPv4)
                ?: throw IllegalStateException("Failed to parse destination IPv4 address from packet bytes.")
        }
        set(newAddress) {
            if (!newAddress.isIPv4) throw IllegalArgumentException("New destination address must be IPv4.")
            val oldAddressBytes = ByteArray(4)
            buffer.position(16)
            buffer.get(oldAddressBytes) // Read old address bytes

            // Write new address
            buffer.position(16)
            buffer.put(newAddress.addressBytes)

            updateChecksumForFieldChange(oldAddressBytes, newAddress.addressBytes, 10)
        }

    init {
        if (rawPacketBytes.size < 20) { // Minimum IPv4 header size
            throw IllegalArgumentException("Packet data too short for IPv4 header (${rawPacketBytes.size} bytes).")
        }
        buffer.position(0)
        val versionAndIhl = buffer.get().toUByte()
        version = IPVersion.fromNibble(versionAndIhl shr 4)
            ?: throw IllegalArgumentException("Unknown IP version: ${versionAndIhl shr 4}")
        if (version != IPVersion.IPv4) {
            throw IllegalArgumentException("IPMutablePacket currently only supports IPv4.")
        }
        ipHeaderLength = (versionAndIhl and 0x0Fu).toInt() * 4
        if (ipHeaderLength < 20) {
            throw IllegalArgumentException("Invalid IP header length: $ipHeaderLength bytes.")
        }

        buffer.position(9)
        protocol = TransportProtocol.fromByte(buffer.get().toUByte())
            ?: TransportProtocol.UNKNOWN // Or throw if strict protocol matching needed
    }

    /**
     * Incrementally updates the IP header checksum.
     * This method should be called whenever a 16-bit word (or a sequence of them like an IP address)
     * in the IP header is changed.
     *
     * @param oldFieldBytes The ByteArray representation of the old field value (network byte order).
     * @param newFieldBytes The ByteArray representation of the new field value (network byte order).
     * @param checksumOffset The byte offset of the IP header checksum field (typically 10).
     */
    private fun updateChecksumForFieldChange(oldFieldBytes: ByteArray, newFieldBytes: ByteArray, checksumOffset: Int) {
        if (oldFieldBytes.size != newFieldBytes.size || oldFieldBytes.size % 2 != 0) {
            System.err.println("WARN: Checksum field update for fields of different or odd length not standard. Field old: ${oldFieldBytes.size}, new: ${newFieldBytes.size}")
            // For simplicity, recalculate full checksum if this complex case arises.
            // Or throw, as IP addresses should be 4 bytes.
            recalculateFullChecksum(checksumOffset)
            return
        }

        var currentChecksum = buffer.getShort(checksumOffset).toUShort()
        var sum = currentChecksum.toUInt().inv() and 0xFFFFu // Invert for sum calculation (1's complement sum part)

        // Subtract old field words (add their 1's complement)
        for (i in oldFieldBytes.indices step 2) {
            val oldWord = ByteBuffer.wrap(oldFieldBytes, i, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            sum = onesComplementSum(sum, oldWord.toUInt().inv() and 0xFFFFu)
        }

        // Add new field words
        for (i in newFieldBytes.indices step 2) {
            val newWord = ByteBuffer.wrap(newFieldBytes, i, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            sum = onesComplementSum(sum, newWord.toUInt())
        }

        currentChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(checksumOffset, currentChecksum.toShort())
    }

    private fun onesComplementSum(currentSum: UInt, valueToAdd: UInt): UInt {
        var newSum = currentSum + valueToAdd
        if (newSum > 0xFFFFu) { // If overflow from 16 bits
            newSum = (newSum and 0xFFFFu) + (newSum shr 16) // Add carry back
        }
        return newSum
    }


    /**
     * Recalculates the entire IP header checksum.
     * Useful if multiple fields change or incremental update is complex.
     * @param checksumOffset Byte offset of the checksum field in the header (usually 10).
     */
    fun recalculateFullChecksum(checksumOffset: Int = 10) {
        if (ipHeaderLength < 20 || rawPacketBytes.size < ipHeaderLength) {
             throw IllegalStateException("IP header length or packet size too small for checksum calculation.")
        }
        // Temporarily zero out the checksum field in the buffer
        buffer.putShort(checksumOffset, 0.toShort())

        var sum: UInt = 0u
        for (i in 0 until ipHeaderLength step 2) {
            // Ensure we don't read past header if ipHeaderLength is odd (though it shouldn't be)
            if (i + 1 < ipHeaderLength) {
                sum = onesComplementSum(sum, buffer.getShort(i).toUShort().toUInt())
            } else {
                // This case should not happen for valid IP headers (IHL is in 4-byte words)
                // If it did, pad with a zero byte for checksum calculation.
                sum = onesComplementSum(sum, (buffer.get(i).toUByte().toUInt() shl 8))
            }
        }

        val finalChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(checksumOffset, finalChecksum.toShort())
    }

    /**
     * Returns the underlying packet data as a ByteArray.
     * Note that this is the mutable buffer; changes to it affect the packet.
     */
    fun getPacketData(): ByteArray {
        return rawPacketBytes
    }
}
