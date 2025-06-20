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
        // Assuming BinaryDataScanner.kt is in the same package or imported.
        // The Swift version used littleEndian: true. Our BinaryDataScanner uses bigEndian by default for network order.
        // However, checksum calculation is about summing words as they are, and then the host's endianness for the odd byte.
        // The provided Swift BinaryDataScanner was initialized with littleEndian = true.
        // Let's stick to that for direct translation, though IP checksum usually deals with network byte order (big endian).
        // For checksum calculation, words are typically processed in network byte order.
        // The provided Swift code's BinaryDataScanner was set to littleEndian: true. This is unusual for network checksums.
        // Let's assume the *intent* was to process bytes as they appear in the array, forming 16-bit words.
        // The Kotlin BinaryDataScanner uses ByteBuffer which respects specific endian order.
        // If data is from network, it's big-endian. If it's host-generated and then checksummed, it depends.
        // Given the "Intel and ARM are both little endian" comment in Swift, it implies it might be processing host-order data.
        // Let's use the Kotlin BinaryDataScanner configured for Little Endian to match the Swift code's behavior.
        val scanner = BinaryDataScanner(data, littleEndian = true) // Match Swift version's endianness choice
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
