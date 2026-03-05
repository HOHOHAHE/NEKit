package nekit.IPStack.Packet

import nekit.Utils.Port
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Assuming IPMutablePacket.kt, Port.kt are available.
// IPMutablePacket.kt needs to have `buffer` and `ipHeaderLength` as `protected`
// and a method like `protected fun updateChecksumWord(oldWord: UShort, newWord: UShort, checksumFieldByteOffset: Int)`

open class TCPMutablePacket(rawPacketBytes: ByteArray) : IPMutablePacket(rawPacketBytes) {

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
        if (super.protocol != nekit.IPStack.TransportProtocol.TCP) {
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
            return Port(portShort.toUShort().toInt())
        }
        set(newPort) {
            val oldPortValue = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.SOURCE_PORT).toUShort()
            buffer.putShort(ipHeaderLength + TCPHeaderOffsets.SOURCE_PORT, newPort.hostOrderValue.toShort())
            updateChecksumsForPortChange(oldPortValue, newPort.hostOrderValue.toUShort())
        }

    var destinationPort: Port
        get() {
            val portShort = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT)
            return Port(portShort.toUShort().toInt())
        }
        set(newPort) {
            val oldPortValue = buffer.getShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT).toUShort()
            buffer.putShort(ipHeaderLength + TCPHeaderOffsets.DESTINATION_PORT, newPort.hostOrderValue.toShort())
            updateChecksumsForPortChange(oldPortValue, newPort.hostOrderValue.toUShort())
        }

    private fun updateChecksumsForPortChange(oldPortNetOrder: UShort, newPortNetOrder: UShort) {
        updateTCPChecksumWord(oldPortNetOrder, newPortNetOrder, ipHeaderLength + TCPHeaderOffsets.CHECKSUM)
    }

    protected fun updateTCPChecksumWord(oldWord: UShort, newWord: UShort, tcpChecksumFieldByteOffset: Int) {
        var currentTCPChecksum = buffer.getShort(tcpChecksumFieldByteOffset).toUShort()
        var sum = onesComplementSum(currentTCPChecksum.toUInt().inv() and 0xFFFFu, oldWord.toUInt().inv() and 0xFFFFu)
        sum = onesComplementSum(sum, newWord.toUInt())
        currentTCPChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(tcpChecksumFieldByteOffset, currentTCPChecksum.toShort())
    }

    fun recalculateTCPChecksum() {
        val tcpChecksumOffsetInTcpHeader = TCPHeaderOffsets.CHECKSUM
        val tcpChecksumOffsetInIpPacket = ipHeaderLength + tcpChecksumOffsetInTcpHeader

        buffer.putShort(tcpChecksumOffsetInIpPacket, 0.toShort())

        var pseudoHeaderSum: UInt = 0u
        sourceAddress?.addressBytes?.let { pseudoHeaderSum = sumBytesAsWords(pseudoHeaderSum, it) }
        destinationAddress?.addressBytes?.let { pseudoHeaderSum = sumBytesAsWords(pseudoHeaderSum, it) }
        pseudoHeaderSum = onesComplementSum(pseudoHeaderSum, protocol.value.toUInt())

        val tcpSegmentLength = rawPacketBytes.size - ipHeaderLength
        if (tcpSegmentLength < 0) throw IllegalStateException("IP Header length greater than packet size.")
        pseudoHeaderSum = onesComplementSum(pseudoHeaderSum, tcpSegmentLength.toUInt())

        var tcpSegmentSum = pseudoHeaderSum
        for (i in ipHeaderLength until rawPacketBytes.size step 2) {
            if (i + 1 < rawPacketBytes.size) {
                tcpSegmentSum = onesComplementSum(tcpSegmentSum, buffer.getShort(i).toUShort().toUInt())
            } else {
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
                sum = onesComplementSum(sum, (bytes[i].toUByte().toUInt() shl 8))
            }
        }
        return sum
    }
}
