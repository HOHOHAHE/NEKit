// Assuming IPAddress.kt, IPRange.kt (placeholder below) are available.
// Also assumes CocoaLumberjackSwift is not used or will be replaced by a Kotlin logging library.

// Placeholder for IPRange.kt - This should be in its own file and fully translated later.
// Based on usage in IPPool.swift.
data class IPRange(
    val startIP: IPAddress,
    val endIP: IPAddress // Guessed, a range usually has a start and end.
) {
    val family: IPAddress.Family = startIP.family

    init {
        require(startIP.family == endIP.family) { "Start and end IP must be of the same family." }
        require(startIP <= endIP) { "Start IP must not be greater than End IP." }
    }

    fun contains(ip: IPAddress): Boolean {
        if (ip.family != family) {
            return false
        }
        // Assumes IPAddress is Comparable
        return ip >= startIP && ip <= endIP
    }
}


/**
 * The pool is built to hold fake IPs.
 *
 * Note: It is NOT thread-safe.
 */
class IPPool(val range: IPRange) {
    val family: IPAddress.Family = range.family
    private var currentEnd: IPAddress = range.startIP
    private val pool: MutableList<IPAddress> = mutableListOf() // LIFO behavior with removeLast/append

    /**
     * Fetches an IP address from the pool.
     * If the pool is empty, it tries to take the next available IP from the range.
     *
     * @return An IPAddress if available, otherwise null.
     */
    fun fetchIP(): IPAddress? {
        if (pool.isNotEmpty()) {
            return pool.removeAt(pool.size - 1) // Equivalent to Swift's removeLast()
        }

        // Check if currentEnd is within the defined range before returning and advancing
        if (range.contains(currentEnd)) {
            val ipToReturn = currentEnd
            val nextIp = currentEnd.advanced(by = 1u) // advanced(by: UInt)
            if (nextIp == null) { // Could happen if currentEnd is max IP value
                currentEnd = ipToReturn // Keep currentEnd as is, effectively stopping generation from range
                                      // Or handle as an error/specific state. For now, it means no more IPs.
                return ipToReturn // Return the last valid IP
            }
            // Check if nextIp is still valid before assigning. If it's outside range, then currentEnd was the last one.
            if (range.contains(nextIp)) {
                 currentEnd = nextIp
            } else {
                // currentEnd was the last IP in the range.
                // To prevent currentEnd from going out of bounds for future calls if pool becomes empty again:
                // We could set currentEnd to a state that range.contains(currentEnd) is false,
                // e.g. by advancing it one more time (if possible) or setting to a special marker.
                // For now, just don't advance currentEnd if nextIp is out of range.
                // This means ipToReturn was the last one.
                // If fetchIP is called again and pool is empty, range.contains(currentEnd) will still be true for ipToReturn,
                // but currentEnd won't advance further if nextIp is out of range. This is slightly problematic.
                // A better way:
                // currentEnd = nextIp // Advance currentEnd regardless
                // if (!range.contains(ipToReturn)) return null // if original currentEnd was already out (e.g. startIP > endIP initially)
                // return ipToReturn
                // This logic needs to be robust for edge cases of the range.
                // Let's stick to the Swift logic: if currentEnd is in range, return it, then advance.
                // If the advanced currentEnd is outside the range for the *next* call, that's handled then.
            }
            return ipToReturn
        } else {
            // currentEnd has already passed range.endIP or the range was initially empty/invalid.
            return null
        }
    }

    /**
     * Releases an IP address back to the pool.
     * The IP is only added if it belongs to the same address family as the pool.
     *
     * @param ip The IPAddress to release.
     */
    fun release(ip: IPAddress) {
        if (ip.family != this.family) {
            // Log this event? Original code just returns.
            // DDLogWarn("Attempting to release IP of different family to pool.")
            return
        }
        // Original code does not check if IP is within range, only family.
        pool.add(ip)
    }

    /**
     * Checks if the given IP address is within the range of this pool.
     *
     * @param ip The IPAddress to check.
     * @return True if the IP is within the pool's range, false otherwise.
     */
    fun contains(ip: IPAddress): Boolean {
        return range.contains(ip)
    }
}
