package com.example.nekit.IPStack.Packet

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Assuming IPMutablePacket.kt, Port.kt are available.
// IPMutablePacket.kt needs to have `buffer` and `ipHeaderLength` as `protected`
// and a method like `protected fun updateChecksumWord(oldWord: UShort, newWord: UShort, checksumFieldByteOffset: Int)`

class TCPMutablePacket(rawPacketBytes: ByteArray) : IPMutablePacket(rawPacketBytes) {

    // TCP header fields offsets relative to the start of the TCP header
    private object TCPHeaderOffsets {
        const val SOURCE_PORT = 0
        const val DESTINATION_PORT = 2
        // SequenceNumber = 4 (UInt32)
        // AcknowledgementNumber = 8 (UInt32)
        // DataOffsetReservedFlags = 12 (UInt16) -> DataOffset (4 bits), Reserved (3 bits), NS (1 bit), Flags (CWR,ECE,URG,ACK,PSH,RST,SYN,FIN - 8 bits)
        // WindowSize = 14 (UInt16)
        const val CHECKSUM = 16 // (UInt16)
        // UrgentPointer = 18 (UInt16)
        // Options...
    }

    // Ensure this packet is indeed TCP, otherwise operations are invalid.
    init {
        if (super.protocol != TransportProtocol.TCP) {
            throw IllegalArgumentException("Packet is not TCP (protocol: ${super.protocol}), cannot use TCPMutablePacket.")
        }
        if (rawPacketBytes.size < super.ipHeaderLength + 20) { // Minimum TCP header size is 20 bytes
            throw IllegalArgumentException("Packet data too short for TCP header. IPHeaderLen: ${super.ipHeaderLength}, TotalLen: ${rawPacketBytes.size}")
        }
    }

    var sourcePort: Port
        get() {
            // Accessing protected 'buffer' and 'ipHeaderLength' from IPMutablePacket
            val portShort = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.SOURCE_PORT)
            return Port(portShort.toUShort()) // Port constructor expects network order value
        }
        set(newPort) {
            val oldPortValue = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.SOURCE_PORT).toUShort()
            buffer.putShort(ipHeaderLength + TCPHeaderOffsets.SOURCE_PORT, newPort.networkOrderValue.toShort())
            // Update both IP and TCP checksums due to port change (part of pseudo-header for TCP)
            updateChecksumsForPortChange(oldPortValue, newPort.networkOrderValue)
        }

    var destinationPort: Port
        get() {
            val portShort = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT)
            return Port(portShort.toUShort())
        }
        set(newPort) {
            val oldPortValue = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT).toUShort()
            buffer.putShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT, newPort.networkOrderValue.toShort())
            updateChecksumsForPortChange(oldPortValue, newPort.networkOrderValue)
        }

    // TODO: Add getters/setters for other TCP fields (SeqNum, AckNum, Flags, Window, etc.)
    // Each setter that modifies a field covered by the TCP checksum must call updateTCPChecksumWord or recalculateTCPChecksum.

    /**
     * Updates IP and TCP checksums when a TCP port changes.
     * Ports are part of the TCP pseudo-header, so changing them affects the TCP checksum.
     * IP checksum is not directly affected by TCP port changes unless IP addresses also change,
     * but the original Swift code called super.updateChecksum. This might be an error or
     * specific context in Swift's IPMutablePacket's `updateChecksum(type: .Port)` if it did something special.
     * For now, assuming only TCP checksum needs update for port change, unless IP layer checksum has to be
     * recalculated due to how incremental updates are chained.
     * The Swift `updateChecksum` in `TCPMutablePacket` called `super.updateChecksum` (for IP checksum)
     * and then `updateChecksum(oldValue, newValue, at: IPHeaderLength + 16)` (for TCP checksum).
     * This implies that any 16-bit field change requires both checksums to be updated if that
     * generic `updateChecksum` was used. This is generally incorrect. IP checksum covers IP header only.
     * TCP checksum covers TCP pseudo-header, TCP header, TCP payload.
     *
     * Let's assume the intent of calling super.updateChecksum in Swift for a port change was a mistake
     * or specific to a simplified checksum model there. Here, we will only update the TCP checksum
     * for a port change. If IP fields were also changing, they'd call their own updaters.
     */
    private fun updateChecksumsForPortChange(oldPortNetOrder: UShort, newPortNetOrder: UShort) {
        // For TCP, ports are part of the pseudo-header.
        // So, changing a port requires recalculating the TCP checksum or using incremental update for it.
        updateTCPChecksumWord(oldPortNetOrder, newPortNetOrder, ipHeaderLength + TCPHeaderOffsets.CHECKSUM)
    }

    /**
     * Incrementally updates a single 16-bit word for the TCP checksum.
     *
     * @param oldWord The old 16-bit value (network byte order).
     * @param newWord The new 16-bit value (network byte order).
     * @param tcpChecksumFieldByteOffset The byte offset of the TCP checksum field itself,
     *                                   relative to the start of the IP packet.
     */
    protected fun updateTCPChecksumWord(oldWord: UShort, newWord: UShort, tcpChecksumFieldByteOffset: Int) {
        // This uses the same logic as IPMutablePacket.updateChecksumWord, but for the TCP checksum field.
        // It's protected, assuming IPMutablePacket provides this.
        // If IPMutablePacket.updateChecksumWord is not suitable (e.g. private or IP specific),
        // then the logic must be duplicated or refactored into a common utility.
        // For now, assuming IPMutablePacket has a suitable protected method:
        // super.updateChecksumWord(oldWord, newWord, tcpChecksumFieldByteOffset)
        // If not, we need to implement it here or make it available.
        // Let's assume we have to implement it here if not directly available from superclass in desired form.

        var currentTCPChecksum = buffer.getShort(tcpChecksumFieldByteOffset).toUShort()
        // The sum for TCP checksum includes pseudo-header, TCP header, and TCP data.
        // Incremental update: new_checksum = ~( ~(old_checksum) + ~(old_word) + new_word )
        // This is equivalent to: new_sum = (current_sum_without_complement) - old_word + new_word
        // where current_sum_without_complement = ~old_checksum.

        var sum = currentTCPChecksum.toUInt().inv() and 0xFFFFu // Get the sum part from current checksum

        sum = onesComplementSum(sum, oldWord.toUInt().inv() and 0xFFFFu) // Subtract old word (add its complement)
        sum = onesComplementSum(sum, newWord.toUInt())                   // Add new word

        currentTCPChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(tcpChecksumFieldByteOffset, currentTCPChecksum.toShort())
    }

    // Helper for 1's complement sum, duplicated from IPMutablePacket if not accessible
    // Or better, place in a common ChecksumUtils object.
    private fun onesComplementSum(currentSum: UInt, valueToAdd: UInt): UInt {
        var newSum = currentSum + valueToAdd
        if (newSum > 0xFFFFu) { // If overflow from 16 bits
            newSum = (newSum and 0xFFFFu) + (newSum shr 16) // Add carry back
        }
        return newSum
    }


    /**
     * Recalculates the entire TCP checksum.
     * This involves the TCP pseudo-header, the TCP header (with checksum field zeroed), and TCP payload.
     */
    fun recalculateTCPChecksum() {
        val tcpChecksumOffsetInTcpHeader = TCPHeaderOffsets.CHECKSUM
        val tcpChecksumOffsetInIpPacket = ipHeaderLength + tcpChecksumOffsetInTcpHeader

        // 1. Zero out checksum field in buffer for calculation
        buffer.putShort(tcpChecksumOffsetInIpPacket, 0.toShort())

        // 2. Calculate pseudo-header sum
        // Source IP (4 bytes), Dest IP (4 bytes), 0x00, Protocol (1 byte), TCP Length (2 bytes)
        var pseudoHeaderSum: UInt = 0u
        sourceAddress?.addressBytes?.let { pseudoHeaderSum = sumBytesAsWords(pseudoHeaderSum, it) }
        destinationAddress?.addressBytes?.let { pseudoHeaderSum = sumBytesAsWords(pseudoHeaderSum, it) }
        pseudoHeaderSum = onesComplementSum(pseudoHeaderSum, protocol.rawValue.toUInt()) // Protocol is in low byte, high byte is 0

        val tcpSegmentLength = rawPacketBytes.size - ipHeaderLength
        if (tcpSegmentLength < 0) throw IllegalStateException("IP Header length greater than packet size.")
        pseudoHeaderSum = onesComplementSum(pseudoHeaderSum, tcpSegmentLength.toUInt())

        // 3. Sum TCP header and TCP payload
        var tcpSegmentSum = pseudoHeaderSum
        for (i in ipHeaderLength until rawPacketBytes.size step 2) {
            if (i + 1 < rawPacketBytes.size) {
                tcpSegmentSum = onesComplementSum(tcpSegmentSum, buffer.getShort(i).toUShort().toUInt())
            } else {
                // Odd byte at end of payload, pad with zero for checksum
                tcpSegmentSum = onesComplementSum(tcpSegmentSum, (buffer.get(i).toUByte().toUInt() shl 8))
            }
        }

        val finalTCPChecksum = (tcpSegmentSum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(tcpChecksumOffsetInIpPacket, finalTCPChecksum.toShort())
    }

    private fun sumBytesAsWords(currentSum: UInt, bytes: ByteArray): UInt {
        var sum = currentSum
        for (i in bytes.indices step 2) {
            if (i + 1 < bytes.size) {
                val word = ((bytes[i].toUInt() and 0xFFu shl 8) or (bytes[i+1].toUInt() and 0xFFu))
                sum = onesComplementSum(sum, word)
            } else {
                // This case should not happen for IP addresses (4 bytes)
                sum = onesComplementSum(sum, (bytes[i].toUInt() and 0xFFu shl 8))
            }
        }
        return sum
    }
}
