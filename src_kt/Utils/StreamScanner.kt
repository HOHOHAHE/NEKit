package nekit.Utils

import java.io.ByteArrayOutputStream

// Helper function to find a byte pattern in a ByteArray
// Returns the starting index of the pattern, or -1 if not found.
// This version searches from a given startIndex towards the end.
// For backward search behavior similar to original, logic in addAndScan will define search window.
private fun ByteArray.indexOfPattern(pattern: ByteArray, startIndex: Int = 0): Int {
    if (pattern.isEmpty() || this.size < pattern.size + startIndex) return -1
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


open class StreamScanner(
    private val pattern: ByteArray,
    private val maximumLength: Int
) {
    private val receivedStream: ByteArrayOutputStream = ByteArrayOutputStream()
    var finished: Boolean = false
        private set

    val currentLength: Int
        get() = receivedStream.size()

    /**
     * Adds data to the scanner and checks for the pattern.
     *
     * @param data The new data to add.
     * @return
     *   - `null` if the pattern is not yet found and maximum length is not exceeded.
     *   - `Pair(null, accumulatedData)` if maximum length is exceeded. `accumulatedData` is all data received so far.
     *   - `Pair(foundData, remainderData)` if the pattern is found.
     *     `foundData` is the data from the beginning up to and including the pattern.
     *     `remainderData` is the data after the pattern.
     */
    open fun addAndScan(data: ByteArray): Pair<ByteArray?, ByteArray>? {
        if (finished) {
            // Or throw an exception, e.g., IllegalStateException("Scanner is already finished.")
            // Depending on desired behavior for subsequent calls after finished.
            // The original Swift code returns nil, implying it's okay to call but does nothing.
            return null
        }

        val previousLength = receivedStream.size()
        receivedStream.write(data)
        val allReceivedData = receivedStream.toByteArray()

        // Optimization: only scan the part of the data that could contain the new pattern occurrence.
        // Start search from (previous length - pattern length + 1) to cover cases where pattern spans old and new data.
        // The Swift code `max(0, receivedData.length - pattern.count - data.count)` is a bit different.
        // It seems to imply `data.count` is the *newly added data length*.
        // `receivedData.length - pattern.count - data.count` could be `currentTotalLength - pattern.size - newData.size`.
        // A simpler approach for `indexOfPattern` is to search in a window.
        // Let's search from `max(0, previousLength - pattern.size + 1)`.
        val searchStartIndex = maxOf(0, previousLength - pattern.size + 1)

        // The Swift code uses .backwards search. For this, we'd need to implement indexOfPattern to search backwards
        // or iterate from searchStartIndex up to (allReceivedData.size - pattern.size).
        // The provided indexOfPattern searches forwards.
        // If multiple patterns exist, this forward search will find the first one from searchStartIndex.
        // Swift's .backwards from a calculated start index might find the *last* pattern in the search window.
        // For typical delimiter scanning (like CRLF), usually the first occurrence is desired.
        // Let's assume first occurrence from searchStartIndex is okay.

        val foundIndex = allReceivedData.indexOfPattern(pattern, startIndex = searchStartIndex)

        if (foundIndex != -1) {
            finished = true
            val foundEndIndex = foundIndex + pattern.size
            val foundData = allReceivedData.copyOfRange(0, foundEndIndex)
            val remainderData = allReceivedData.copyOfRange(foundEndIndex, allReceivedData.size)
            return Pair(foundData, remainderData)
        } else {
            if (allReceivedData.size > maximumLength) {
                finished = true
                return Pair(null, allReceivedData) // Error: Max length exceeded
            } else {
                return null // Pattern not found yet, continue
            }
        }
    }

    /**
     * Resets the scanner to its initial state.
     */
    open fun reset() {
        receivedStream.reset()
        finished = false
    }
}
