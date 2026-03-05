package nekit.IPStack.Packet

import nekit.IPStack.IPVersion
import nekit.IPStack.TransportProtocol
import nekit.Utils.IPAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class IPMutablePacket(protected val rawPacketBytes: ByteArray) {

    protected val buffer: ByteBuffer = ByteBuffer.wrap(rawPacketBytes).order(ByteOrder.BIG_ENDIAN)

    lateinit var version: IPVersion
    lateinit var protocol: TransportProtocol
    var ipHeaderLength: Int = 0

    var sourceAddress: IPAddress
        get() {
            val addrBytes = ByteArray(4)
            buffer.position(12)
            buffer.get(addrBytes)
            return IPAddress.fromBytes(addrBytes)!!
        }
        set(newAddress) {
            if (newAddress.addressBytes.size != 4) throw IllegalArgumentException("New source address must be IPv4.")
            val oldAddressBytes = ByteArray(4)
            buffer.position(12)
            buffer.get(oldAddressBytes)

            buffer.position(12)
            buffer.put(newAddress.addressBytes)

            updateChecksumForFieldChange(oldAddressBytes, newAddress.addressBytes, 10)
        }

    var destinationAddress: IPAddress
        get() {
            val addrBytes = ByteArray(4)
            buffer.position(16)
            buffer.get(addrBytes)
            return IPAddress.fromBytes(addrBytes)!!
        }
        set(newAddress) {
            if (newAddress.addressBytes.size != 4) throw IllegalArgumentException("New destination address must be IPv4.")
            val oldAddressBytes = ByteArray(4)
            buffer.position(16)
            buffer.get(oldAddressBytes)

            buffer.position(16)
            buffer.put(newAddress.addressBytes)

            updateChecksumForFieldChange(oldAddressBytes, newAddress.addressBytes, 10)
        }

    init {
        if (rawPacketBytes.size < 20) {
            throw IllegalArgumentException("Packet data too short for IPv4 header (${rawPacketBytes.size} bytes).")
        }
        buffer.position(0)
        val versionAndIhl = buffer.get().toUByte()
        version = IPVersion.fromInt(versionAndIhl.toInt() shr 4)
            ?: throw IllegalArgumentException("Unknown IP version: ${versionAndIhl.toInt() shr 4}")
        if (version != IPVersion.IPV4) {
            throw IllegalArgumentException("IPMutablePacket currently only supports IPv4.")
        }
        ipHeaderLength = (versionAndIhl.toInt() and 0x0F) * 4
        if (ipHeaderLength < 20) {
            throw IllegalArgumentException("Invalid IP header length: $ipHeaderLength bytes.")
        }

        buffer.position(9)
        protocol = TransportProtocol.fromInt(buffer.get().toUByte().toInt())
            ?: throw IllegalArgumentException("Unknown transport protocol")
    }

    private fun updateChecksumForFieldChange(
        oldFieldBytes: ByteArray,
        newFieldBytes: ByteArray,
        checksumOffset: Int
    ) {
        if (oldFieldBytes.size != newFieldBytes.size || oldFieldBytes.size % 2 != 0) {
            recalculateFullChecksum(checksumOffset)
            return
        }

        var currentChecksum = buffer.getShort(checksumOffset).toUShort()
        var sum = currentChecksum.toUInt().inv() and 0xFFFFu

        for (i in oldFieldBytes.indices step 2) {
            val oldWord = ByteBuffer.wrap(oldFieldBytes, i, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            sum = onesComplementSum(sum, oldWord.toUInt().inv() and 0xFFFFu)
        }

        for (i in newFieldBytes.indices step 2) {
            val newWord = ByteBuffer.wrap(newFieldBytes, i, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            sum = onesComplementSum(sum, newWord.toUInt())
        }

        currentChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(checksumOffset, currentChecksum.toShort())
    }

    protected fun onesComplementSum(currentSum: UInt, valueToAdd: UInt): UInt {
        var newSum = currentSum + valueToAdd
        if (newSum > 0xFFFFu) {
            newSum = (newSum and 0xFFFFu) + (newSum shr 16)
        }
        return newSum
    }

    fun recalculateFullChecksum(checksumOffset: Int = 10) {
        if (ipHeaderLength < 20 || rawPacketBytes.size < ipHeaderLength) {
            throw IllegalStateException("IP header length or packet size too small for checksum calculation.")
        }
        buffer.putShort(checksumOffset, 0.toShort())

        var sum: UInt = 0u
        for (i in 0 until ipHeaderLength step 2) {
            if (i + 1 < ipHeaderLength) {
                sum = onesComplementSum(sum, buffer.getShort(i).toUShort().toUInt())
            } else {
                sum = onesComplementSum(sum, (buffer.get(i).toUByte().toUInt() shl 8))
            }
        }

        val finalChecksum = (sum.inv() and 0xFFFFu).toUShort()
        buffer.putShort(checksumOffset, finalChecksum.toShort())
    }

    fun getPacketData(): ByteArray {
        return rawPacketBytes
    }
}
