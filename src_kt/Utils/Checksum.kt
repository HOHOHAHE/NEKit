package com.example.nekit.Utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility class for computing checksums, specifically for network protocols.
 */
object Checksum {

    /**
     * Computes the Internet checksum (RFC 1071) for a given byte array.
     * This is a 16-bit one's complement sum.
     *
     * @param data The byte array for which to compute the checksum.
     * @param initialSum An optional initial sum to continue a checksum calculation.
     * @return The computed 16-bit checksum as a UShort.
     */
    fun computeChecksum(data: ByteArray, initialSum: UInt = 0u): UShort {
        var sum = initialSum
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        // Sum 16-bit words
        var i = 0
        while (i < data.size / 2) {
            sum += buffer.getShort(i * 2).toUShort().toUInt()
            i++
        }

        // Add any odd-length byte
        if (data.size % 2 != 0) {
            sum += (data[data.size - 1].toUByte().toUInt() shl 8) // Pad with zero to the right
        }

        // Fold 32-bit sum to 16 bits
        while (sum shr 16 > 0u) {
            sum = (sum and 0xFFFFu) + (sum shr 16)
        }

        // One's complement
        return sum.inv().toUShort()
    }
}