package nekit.Utils

import java.io.ByteArrayOutputStream



// This is a simple wrapper for a byte buffer.
// It uses ByteArrayOutputStream internally to handle dynamic sizing.
class Buffer(initialCapacity: Int = 32) {
    private var stream: ByteArrayOutputStream = ByteArrayOutputStream(initialCapacity)
    private var internalBuffer: ByteArray = ByteArray(0) // Cache for the stream's content
    private var offset = 0 // Read offset for the internalBuffer

    // The `consolidateStream()` method was found to be redundant with the logic
    // already present in `getReadableBuffer()`. It has been removed.

    // Ensures internalBuffer is up-to-date with any appended data in stream
    // and provides a view from the current offset.
    private fun getReadableBuffer(): ByteArray {
        if (stream.size() > 0) {
            // If there's anything in the stream, it means new data was appended.
            // We need to combine it with the existing unread part of internalBuffer.
            val unreadFromInternal = if (internalBuffer.isNotEmpty() && offset < internalBuffer.size) {
                internalBuffer.copyOfRange(offset, internalBuffer.size)
            } else {
                ByteArray(0)
            }
            internalBuffer = unreadFromInternal + stream.toByteArray()
            stream.reset()
            offset = 0
        }
        return internalBuffer
    }


    val left: Int
        get() = getReadableBuffer().size - offset

    val count: Int
        get() = getReadableBuffer().size

    val currentData: ByteArray
        get() {
            val readable = getReadableBuffer()
            return readable.copyOfRange(offset, readable.size)
        }

    fun append(data: ByteArray) {
        stream.write(data)
    }

    // Squeezes the buffer by removing the already read part (before offset)
    fun squeeze() {
        val currentReadable = getReadableBuffer() // Consolidates stream if needed
        if (offset > 0 && offset <= currentReadable.size) {
            internalBuffer = currentReadable.copyOfRange(offset, currentReadable.size)
        }
        offset = 0
        stream.reset() // Any new data was already consolidated by getReadableBuffer
                       // and anything before offset in internalBuffer is now removed.
    }

    fun get(length: Int): ByteArray? {
        val readableBuffer = getReadableBuffer()
        if (length < 0 || offset + length > readableBuffer.size) {
            return null
        }
        val result = readableBuffer.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    // Helper to find sub-array (pattern)
    private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
        if (pattern.isEmpty() || this.size < pattern.size) return -1
        for (i in startIndex..(this.size - pattern.size)) {
            var found = true
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    fun get(pattern: ByteArray): ByteArray? {
        val readableBuffer = getReadableBuffer()
        val searchBuffer = if (offset > 0) readableBuffer.copyOfRange(offset, readableBuffer.size) else readableBuffer

        val rangeEnd = searchBuffer.indexOf(pattern)
        if (rangeEnd == -1) {
            return null
        }
        // The rangeEnd is relative to searchBuffer, which starts at original 'offset'
        // The length to get is from current 'offset' up to and including the pattern
        val lengthToGet = rangeEnd + pattern.size
        return get(lengthToGet)
    }

    fun getAll(): ByteArray? {
        val readableBuffer = getReadableBuffer()
        return get(readableBuffer.size - offset)
    }

    fun setBack(length: Int) {
        if (length < 0) return
        offset -= length
        if (offset < 0) {
            offset = 0
        }
    }

    fun release() {
        stream.reset()
        internalBuffer = ByteArray(0)
        offset = 0
    }

    fun skip(step: Int) {
        if (step < 0) { // Consider if skipping backwards is allowed, for now only forward
            setBack(-step)
            return
        }
        val readableBuffer = getReadableBuffer() // ensure buffer is consolidated
        if (offset + step > readableBuffer.size) {
            offset = readableBuffer.size // Go to the end
        } else {
            offset += step
        }
    }
}
