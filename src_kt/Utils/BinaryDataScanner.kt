import java.nio.ByteBuffer
import java.nio.ByteOrder

// Note: Kotlin does not have direct unsigned types like Swift's UInt8, UInt16, UInt32, UInt64.
// We use Byte, Short, Int, Long respectively. Care must be taken with operations
// where unsigned behavior is critical (e.g., when values exceed the positive range of signed types).

interface BinaryReadable<T> {
    fun toLittleEndian(): T
    fun toBigEndian(): T
}

// For Byte (UInt8), endianness doesn't change the value.
fun Byte.toLittleEndian(): Byte = this
fun Byte.toBigEndian(): Byte = this

// For Short (UInt16)
fun Short.toLittleEndian(): Short {
    return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) this
    else java.lang.Short.reverseBytes(this)
}

fun Short.toBigEndian(): Short {
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this
    else java.lang.Short.reverseBytes(this)
}

// For Int (UInt32)
fun Int.toLittleEndian(): Int {
    return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) this
    else java.lang.Integer.reverseBytes(this)
}

fun Int.toBigEndian(): Int {
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this
    else java.lang.Integer.reverseBytes(this)
}

// For Long (UInt64)
fun Long.toLittleEndian(): Long {
    return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) this
    else java.lang.Long.reverseBytes(this)
}

fun Long.toBigEndian(): Long {
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this
    else java.lang.Long.reverseBytes(this)
}


open class BinaryDataScanner(
    private val data: ByteArray,
    private val littleEndian: Boolean
) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(data).apply {
        order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    }

    val remaining: Int
        get() = buffer.remaining()

    var position: Int
        get() = buffer.position()
        set(value) {
            buffer.position(value)
        }

    open fun skip(to: Int) {
        if (to < 0 || to > data.size) {
            throw IndexOutOfBoundsException("Cannot skip to position $to, data size is ${data.size}")
        }
        buffer.position(to)
    }

    open fun advance(by: Int) {
        val newPosition = buffer.position() + by
        if (newPosition < 0 || newPosition > data.size) {
            throw IndexOutOfBoundsException("Cannot advance by $by, current position ${buffer.position()}, data size is ${data.size}")
        }
        buffer.position(newPosition)
    }

    // Generic read function is hard to make truly generic like Swift's due to type erasure and varying sizes.
    // We provide specific read functions instead.

    open fun readByte(): Byte? {
        if (remaining < 1) return null
        return buffer.get()
    }

    open fun readInt8(): Byte? { // Alias for readByte for clarity with signedness
        return readByte()
    }

    open fun readUInt8(): UByte? {
        if (remaining < 1) return null
        return buffer.get().toUByte()
    }

    open fun readShort(): Short? {
        if (remaining < 2) return null
        return buffer.short
    }

    open fun readUInt16(): UShort? {
        if (remaining < 2) return null
        return buffer.short.toUShort()
    }

    open fun readInt(): Int? {
        if (remaining < 4) return null
        return buffer.int
    }

    open fun readUInt32(): UInt? {
        if (remaining < 4) return null
        return buffer.int.toUInt()
    }

    open fun readLong(): Long? {
        if (remaining < 8) return null
        return buffer.long
    }

    open fun readUInt64(): ULong? {
        if (remaining < 8) return null
        return buffer.long.toULong()
    }

    /**
     * Reads a specific number of bytes.
     * @param count The number of bytes to read.
     * @return ByteArray containing the read bytes, or null if not enough bytes are remaining.
     */
    open fun readBytes(count: Int): ByteArray? {
        if (count < 0) throw IllegalArgumentException("Count cannot be negative.")
        if (remaining < count) return null
        val bytes = ByteArray(count)
        buffer.get(bytes)
        return bytes
    }
}
