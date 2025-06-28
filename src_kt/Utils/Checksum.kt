object Checksum {

    fun computeChecksum(data: ByteArray, from: Int = 0, to: Int? = null, withPseudoHeaderChecksum: UInt = 0u): UShort {
        return toChecksum(computeChecksumUnfold(data, from, to, withPseudoHeaderChecksum))
    }

    fun validateChecksum(payload: ByteArray, from: Int = 0, to: Int? = null): Boolean {
        val cs = computeChecksumUnfold(payload, from, to)
        // A valid checksum results in 0 when all 16-bit words (including the checksum itself) are summed up
        // and then folded and inverted. If the checksum is correct, this process yields zero.
        // The original Swift code checks `toChecksum(cs) == 0`.
        // `toChecksum` inverts the sum. So if the sum of all data + checksum is 0xFFFF (all ones),
        // inverting it gives 0.
        return toChecksum(cs) == 0u.toUShort()
    }

    fun computeChecksumUnfold(data: ByteArray, from: Int = 0, to: Int? = null, withPseudoHeaderChecksum: UInt = 0u): UInt {
        // TODO: CRITICAL REVIEW REQUIRED FOR ENDIANNESS.
        // This implementation currently uses `littleEndian = true` for the BinaryDataScanner
        // to match the behavior of the provided Swift source code's BinaryDataScanner, which was
        // also explicitly set to little-endian.
        // STANDARD IP CHECKSUM (RFC 1071) expects data to be summed as 16-bit words in
        // NETWORK BYTE ORDER (BIG-ENDIAN).
        // If the `data` ByteArray parameter contains data already in network byte order (e.g., an IP header
        // read from the network), using a little-endian scanner here will incorrectly swap bytes
        // of each 16-bit word before summing, leading to an incorrect checksum according to RFC 1071.
        // If the intent is to checksum data that is natively little-endian in memory, then this might be
        // what the original Swift code intended, but it would not be a standard IP checksum.
        // For a standard IP checksum, `littleEndian` should be `false`.
        val scanner = BinaryDataScanner(data, littleEndian = true) // MATCHING SWIFT'S UNUSUAL CHOICE
        scanner.skip(to = from)
        var result: UInt = withPseudoHeaderChecksum
        val actualEnd = to ?: data.size

        while (scanner.position + 2 <= actualEnd) {
            // readUInt16() from our Kotlin BinaryDataScanner respects the scanner's endian setting.
            val value = scanner.readUInt16()
            if (value == null) break // Should not happen if logic is correct
            result += value.toUInt()
        }

        if (scanner.position < actualEnd) { // Changed from != to < to handle cases where position might exceed end
            // data is of odd size
            val value = scanner.readUInt8()
            if (value != null) { // Check for null if readByte can return null
                // In checksum, the last odd byte is typically treated as if it's the high byte of a 16-bit word,
                // with the low byte being zero. If scanner is littleEndian, (value << 8) would be wrong.
                // If little endian: it's value + 0x0000 = value.
                // If big endian: it's (value << 8) + 0x0000 = value << 8
                // The Swift code just adds `UInt32(value)`. This implies the byte is treated as `00xx` if little-endian.
                // Or `xx00` if big-endian and it was read as the first byte of a word.
                // Given scanner is littleEndian: true, UInt32(value) means the byte itself is added.
                result += value.toUInt()
            }
        }
        return result
    }

    fun toChecksum(checksum: UInt): UShort {
        var currentSum = checksum
        // Sum up overflows
        while ((currentSum shr 16) != 0u) {
            currentSum = (currentSum shr 16) + (currentSum and 0xFFFFu)
        }
        // Take one's complement
        return currentSum.toUShort().inv()
    }
}
