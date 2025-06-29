package com.example.nekit.IPStack.DNS

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

// Assuming DNSEnums.kt, IPAddress.kt, BinaryDataScanner.kt are available.
// TODO: Replace CocoaLumberjack DDLogError with a Kotlin logging solution.

class DNSMessage {
    var transactionID: UShort = 0u
    var messageType: DNSMessageType = DNSMessageType.QUERY
    var authoritative: Boolean = false
    var truncation: Boolean = false
    var recursionDesired: Boolean = false
    var recursionAvailable: Boolean = false
    var returnCode: DNSReturnCode = DNSReturnCode.SUCCESS // Renamed from status
    var queries: MutableList<DNSQuery> = mutableListOf()
    var answers: MutableList<DNSResource> = mutableListOf()
    var nameservers: MutableList<DNSResource> = mutableListOf()
    var additionals: MutableList<DNSResource> = mutableListOf()

    // Calculated total length of the message in bytes when built
    private val bytesLength: Int
        get() {
            var len = 12 // Header length
            len += queries.sumOf { it.bytesLength }
            len += answers.sumOf { it.bytesLength }
            len += nameservers.sumOf { it.bytesLength }
            len += additionals.sumOf { it.bytesLength }
            return len
        }

    val resolvedIPv4Address: IPAddress?
        get() = answers.firstNotNullOfOrNull { it.ipv4Address }

    val type: DNSType?
        get() = queries.firstOrNull()?.type

    constructor() // For building a new message

    /**
     * Parses a DNS message from a ByteArray payload.
     */
    constructor(payload: ByteArray) {
        // Original Swift used BinaryDataScanner. ByteBuffer is more direct for this.
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        transactionID = buffer.short.toUShort()

        val flags1 = buffer.get().toUByte()
        messageType = if ((flags1 and 0x80u) > 0u) DNSMessageType.RESPONSE else DNSMessageType.QUERY
        // Opcode is ignored in Swift: (flags1 shr 3) and 0x0Fu
        authoritative = (flags1 and 0x04u) > 0u
        truncation = (flags1 and 0x02u) > 0u
        recursionDesired = (flags1 and 0x01u) > 0u

        val flags2 = buffer.get().toUByte()
        recursionAvailable = (flags2 and 0x80u) > 0u
        // Z reserved bits ignored: (flags2 shr 4) and 0x07u
        returnCode = DNSReturnCode.fromRawValue(flags2 and 0x0Fu)
            ?: run {
                System.err.println("Received DNS response with unknown status: ${flags2 and 0x0Fu}.")
                DNSReturnCode.SERVER_FAILURE // Default or throw
            }

        val queryCount = buffer.short.toInt()
        val answerCount = buffer.short.toInt()
        val nameserverCount = buffer.short.toInt()
        val additionalCount = buffer.short.toInt()

        for (i in 0 until queryCount) {
            // Pass the original payload and current position for name parsing context
            DNSQuery.parse(payload, buffer.position())?.let { query ->
                queries.add(query)
                buffer.position(buffer.position() + query.bytesLength)
            } ?: run {
                System.err.println("Failed to parse DNS query ${i+1}")
                // Or throw an exception
                return
            }
        }

        for (i in 0 until answerCount) {
            DNSResource.parse(payload, buffer.position())?.let { resource ->
                answers.add(resource)
                buffer.position(buffer.position() + resource.bytesLength)
            } ?: run {
                System.err.println("Failed to parse DNS answer ${i+1}")
                return
            }
        }

        for (i in 0 until nameserverCount) {
            DNSResource.parse(payload, buffer.position())?.let { resource ->
                nameservers.add(resource)
                buffer.position(buffer.position() + resource.bytesLength)
            } ?: run {
                System.err.println("Failed to parse DNS nameserver ${i+1}")
                return
            }
        }

        for (i in 0 until additionalCount) {
            DNSResource.parse(payload, buffer.position())?.let { resource ->
                additionals.add(resource)
                buffer.position(buffer.position() + resource.bytesLength)
            } ?: run {
                System.err.println("Failed to parse DNS additional record ${i+1}")
                return
            }
        }
    }

    /**
     * Builds the DNS message into a ByteArray.
     * @return The ByteArray representing the DNS message, or null if an error occurs.
     */
    fun build(): ByteArray? {
        val buffer = ByteBuffer.allocate(bytesLength).order(ByteOrder.BIG_ENDIAN)

        if (transactionID == 0u.toUShort()) {
            transactionID = Random.nextInt(0, UShort.MAX_VALUE.toInt() + 1).toUShort()
        }
        buffer.putShort(transactionID.toShort())

        var flags1: UByte = 0u
        if (messageType == DNSMessageType.RESPONSE) flags1 = flags1 or 0x80u // QR
        // Opcode usually 0 for standard query: (opcode.toUByte() shl 3)
        if (authoritative) flags1 = flags1 or 0x04u // AA
        if (truncation) flags1 = flags1 or 0x02u    // TC
        if (recursionDesired) flags1 = flags1 or 0x01u // RD
        buffer.put(flags1.toByte())

        var flags2: UByte = 0u
        if (recursionAvailable) flags2 = flags2 or 0x80u // RA
        // Z reserved: 0
        flags2 = flags2 or returnCode.rawValue   // RCODE
        buffer.put(flags2.toByte())

        buffer.putShort(queries.size.toShort())
        buffer.putShort(answers.size.toShort())
        buffer.putShort(nameservers.size.toShort())
        buffer.putShort(additionals.size.toShort())

        val nameCompressionMap = mutableMapOf<String, Int>() // For writing compressed names

        for (query in queries) {
            if (!query.writeTo(buffer, nameCompressionMap)) return null
        }
        for (resourceList in listOf(answers, nameservers, additionals)) {
            for (resource in resourceList) {
                if (!resource.writeTo(buffer, nameCompressionMap)) return null
            }
        }
        return buffer.array()
    }
}


class DNSQuery(
    val name: String,
    val type: DNSType,
    val klass: DNSClass = DNSClass.IN // Default to IN
) {
    internal val nameBytesLength: Int = DNSNameConverter.calculateNameBytesLength(name) // Includes terminal zero for non-compressed

    val bytesLength: Int
        get() = nameBytesLength + 4 // 2 for type, 2 for class

    internal fun writeTo(buffer: ByteBuffer, compressionMap: MutableMap<String, Int>): Boolean {
        if (!DNSNameConverter.writeName(name, buffer, compressionMap)) return false
        buffer.putShort(type.rawValue.toShort())
        buffer.putShort(klass.rawValue.toShort())
        return true
    }

    companion object {
        /**
         * Parses a DNSQuery from the payload at a given offset.
         * @param payload The full DNS message payload.
         * @param offset The current offset in the payload to start parsing the query.
         * @return DNSQuery object or null if parsing fails.
         */
        fun parse(payload: ByteArray, offset: Int): DNSQuery? {
            return try {
                val (name, nameBytesConsumed) = DNSNameConverter.parseName(payload, offset)
                if (nameBytesConsumed == 0) return null // Error parsing name

                val currentPos = offset + nameBytesConsumed
                if (payload.size < currentPos + 4) return null // Not enough bytes for type and class

                val typeRaw = ByteBuffer.wrap(payload, currentPos, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
                val type = DNSType.fromRawValue(typeRaw) ?: run {
                    System.err.println("DNSQuery: Unknown type $typeRaw")
                    return null
                }

                val klassRaw = ByteBuffer.wrap(payload, currentPos + 2, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
                val klass = DNSClass.fromRawValue(klassRaw) ?: run {
                    System.err.println("DNSQuery: Unknown class $klassRaw")
                    return null
                }
                // The DNSQuery in Swift calculated nameBytesLength differently for parsing.
                // Here, we use the actual consumed length from parseName.
                // The local bytesLength is more for writing.
                DNSQuery(name.trimEnd('.'), type, klass) // name returned by parseName might have trailing dot
            } catch (e: Exception) {
                System.err.println("Error parsing DNSQuery: ${e.message}")
                null
            }
        }
    }
}

class DNSResource(
    val name: String,
    val type: DNSType,
    val klass: DNSClass,
    val ttl: UInt,
    val rdata: ByteArray // Raw resource data
) {
    internal val nameBytesLength: Int = DNSNameConverter.calculateNameBytesLength(name)
    private val rdataLength: UShort = rdata.size.toUShort()

    val bytesLength: Int
        get() = nameBytesLength + 10 + rdata.size // 2 type, 2 class, 4 TTL, 2 RDLENGTH

    val ipv4Address: IPAddress?
        get() = if (type == DNSType.A && klass == DNSClass.IN && rdata.size == 4) {
            IPAddress.fromBytes(rdata, IPAddress.Family.IPv4)
        } else {
            null
        }

    // Add similar accessors for AAAA, MX, CNAME, TXT etc. as needed.
    // e.g. for TXT: val textData: List<String>? get() = if (type == DNSType.TXT) { ... parse rdata ... } else null

    internal fun writeTo(buffer: ByteBuffer, compressionMap: MutableMap<String, Int>): Boolean {
        if (!DNSNameConverter.writeName(name, buffer, compressionMap)) return false
        buffer.putShort(type.rawValue.toShort())
        buffer.putShort(klass.rawValue.toShort())
        buffer.putInt(ttl.toInt())
        buffer.putShort(rdataLength.toShort())
        buffer.put(rdata)
        return true
    }

    companion object {
        fun aRecord(name: String, ttl: UInt, address: IPAddress): DNSResource? {
            if (!address.isIPv4) return null // Or throw
            return DNSResource(name, DNSType.A, DNSClass.IN, ttl, address.addressBytes)
        }

        /**
         * Parses a DNSResource from the payload at a given offset.
         */
        fun parse(payload: ByteArray, offset: Int): DNSResource? {
             return try {
                val (name, nameBytesConsumed) = DNSNameConverter.parseName(payload, offset)
                 if (nameBytesConsumed == 0 && name.isEmpty()) { // name could be empty string for root, but nameBytesConsumed should be 1 (the null byte)
                     System.err.println("DNSResource: Failed to parse name or name is empty with zero consumption.")
                     return null
                 }


                var currentPos = offset + nameBytesConsumed
                if (payload.size < currentPos + 10) return null // Not enough for type, class, TTL, RDLENGTH

                val typeRaw = ByteBuffer.wrap(payload, currentPos, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
                val type = DNSType.fromRawValue(typeRaw) ?: run {
                    System.err.println("DNSResource: Unknown type $typeRaw for name $name")
                    return null
                }
                currentPos += 2

                val klassRaw = ByteBuffer.wrap(payload, currentPos, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
                val klass = DNSClass.fromRawValue(klassRaw) ?: run {
                    System.err.println("DNSResource: Unknown class $klassRaw for name $name")
                    return null
                }
                currentPos += 2

                val ttl = ByteBuffer.wrap(payload, currentPos, 4).order(ByteOrder.BIG_ENDIAN).int.toUInt()
                currentPos += 4

                val rdataLength = ByteBuffer.wrap(payload, currentPos, 2).order(ByteOrder.BIG_ENDIAN).short.toUShort()
                currentPos += 2

                if (payload.size < currentPos + rdataLength.toInt()) return null // Not enough for rdata
                val rdata = payload.copyOfRange(currentPos, currentPos + rdataLength.toInt())

                DNSResource(name.trimEnd('.'), type, klass, ttl, rdata)
            } catch (e: Exception) {
                System.err.println("Error parsing DNSResource: ${e.message}")
                null
            }
        }
    }
}


object DNSNameConverter {
    // Calculates the length of the name when written in DNS label format (uncompressed)
    internal fun calculateNameBytesLength(name: String): Int {
        if (name == ".") return 1 // Root label is a single null byte
        if (name.isEmpty()) return 1 // Empty name treated as root label
        return name.split('.').sumOf { it.length + 1 } + 1 // Sum of (length byte + label) + terminal null byte
    }

    /**
     * Writes a domain name to the ByteBuffer in DNS label format, applying compression.
     * @param name The domain name string (e.g., "www.example.com").
     * @param buffer The ByteBuffer to write to.
     * @param compressionMap A map to store/retrieve offsets of already written names/labels for compression.
     * @return True if successful, false otherwise.
     */
    fun writeName(name: String, buffer: ByteBuffer, compressionMap: MutableMap<String, Int>): Boolean {
        if (name == "." || name.isEmpty()) { // Root label
            buffer.put(0.toByte()) // Single null byte for root
            return true
        }

        var currentName = name
        while (currentName.isNotEmpty()) {
            if (compressionMap.containsKey(currentName)) {
                val offset = compressionMap[currentName]!!
                // Write pointer: 0xC000 ORed with offset (14 bits)
                buffer.putShort((0xC000 or offset).toShort())
                return true
            }
            // Store current position for potential future compression pointer
            compressionMap[currentName] = buffer.position()

            val parts = currentName.split('.', limit = 2)
            val label = parts[0]
            if (label.length > 63) return false // Label too long

            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.UTF_8)) // Or ASCII, but UTF-8 is safer for IDN components

            currentName = if (parts.size > 1) parts[1] else ""
        }
        buffer.put(0.toByte()) // Null terminator for the name sequence
        return true
    }

    /**
     * Parses a domain name from a DNS payload, handling compression pointers.
     * @param payload The full DNS message payload.
     * @param offset The offset within the payload where the name starts.
     * @return Pair of (full domain name string, number of bytes consumed from the original offset for this name).
     *         Returns (empty string, 0) or throws on error.
     */
    @Throws(IllegalArgumentException::class)
    fun parseName(payload: ByteArray, offset: Int): Pair<String, Int> {
        val nameParts = mutableListOf<String>()
        var currentOffset = offset
        var bytesConsumed = 0
        var jumped = false
        val maxJumps = payload.size / 2 + 2 // Heuristic to prevent infinite loops from bad pointers

        var jumpsDone = 0

        while (currentOffset < payload.size && jumpsDone < maxJumps) {
            val lengthByte = payload[currentOffset].toUByte()

            if ((lengthByte and 0xC0u) == 0xC0u) { // Pointer
                if (currentOffset + 1 >= payload.size) throw IllegalArgumentException("Malformed pointer: missing offset byte.")
                if (!jumped) { // Only count bytes consumed before the first jump
                    bytesConsumed += 2
                    jumped = true
                }
                val pointerOffset = (((lengthByte and 0x3Fu).toInt() shl 8) or payload[currentOffset + 1].toUByte().toInt())
                if (pointerOffset >= payload.size) throw IllegalArgumentException("Pointer offset $pointerOffset out of bounds for payload size ${payload.size}")
                currentOffset = pointerOffset
                jumpsDone++
                continue // Restart loop from new offset
            } else if (lengthByte == 0u.toUByte()) { // End of name
                if (!jumped) bytesConsumed += 1
                break
            } else if (lengthByte > 63u) { // Label too long
                 throw IllegalArgumentException("Label length $lengthByte exceeds 63 bytes.")
            }

            // Not a pointer and not end of name, so it's a label length
            if (!jumped) bytesConsumed += (1 + lengthByte.toInt())
            currentOffset += 1 // Move past length byte

            if (currentOffset + lengthByte.toInt() > payload.size) throw IllegalArgumentException("Label extends beyond payload.")

            val labelBytes = payload.copyOfRange(currentOffset, currentOffset + lengthByte.toInt())
            // DNS names are typically ASCII, but can contain UTF-8 in some contexts (though rare for wire format labels).
            // Using UTF-8 for robustness, though strict ASCII might be more correct for some older DNS.
            nameParts.add(String(labelBytes, Charsets.UTF_8)) // Or US_ASCII
            currentOffset += lengthByte.toInt()

        }
        if (jumpsDone >= maxJumps) throw IllegalArgumentException("Too many jumps, likely a pointer loop in DNS name.")
        if (currentOffset >= payload.size && nameParts.isEmpty() && bytesConsumed == 0) { // Started at end of payload
            if (payload[offset].toUByte() == 0u.toUByte()) return Pair("", 1) // Root label at end
            throw IllegalArgumentException("Started parsing name at or beyond payload end without valid termination.")
        }
        if (nameParts.isEmpty()) return Pair(".", if (bytesConsumed == 0 && offset < payload.size && payload[offset].toUByte() == 0u.toUByte()) 1 else bytesConsumed) // Root or empty name

        return Pair(nameParts.joinToString("."), bytesConsumed)
    }
}
