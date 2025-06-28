import org.slf4j.LoggerFactory

// Assuming IPAddress.kt, IPRange.kt (placeholder below) are available.

// Placeholder for IPRange.kt - This should be in its own file and fully translated later.
// Based on usage in IPPool.swift.
// TODO: Replace with actual IPRange.kt from its own file.
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
 * Note: It is NOT thread-safe. Users must ensure synchronized access if used concurrently.
 */
class IPPool(val range: IPRange) {
    companion object {
        private val logger = LoggerFactory.getLogger(IPPool::class.java)
    }

    val family: IPAddress.Family = range.family
    private var currentEnd: IPAddress = range.startIP
    private val pool: MutableList<IPAddress> = mutableListOf() // LIFO behavior with removeLast/append

    /**
     * Fetches an IP address from the pool.
     * If the pool is empty, it tries to take the next available IP from the range.
     *
     * @return An IPAddress if available, otherwise null if the pool is exhausted.
     */
    fun fetchIP(): IPAddress? {
        if (pool.isNotEmpty()) {
            val fetchedIp = pool.removeAt(pool.size - 1)
            logger.debug("Fetched IP {} from pool. Pool size: {}", fetchedIp, pool.size)
            return fetchedIp
        }

        if (range.contains(currentEnd)) {
            val ipToReturn = currentEnd
            val nextIp = currentEnd.advanced(by = 1u) // advanced(by: UInt)

            if (nextIp == null) {
                logger.warn("Failed to advance IP address beyond {} (max reached for range or type). No new IPs available from sequence.", currentEnd)
                // currentEnd remains ipToReturn, so this effectively becomes the last IP available from sequence.
            } else {
                if (range.contains(nextIp)) {
                    currentEnd = nextIp
                } else {
                    // currentEnd was the last IP in the range.
                    // To prevent re-issuing ipToReturn if currentEnd isn't advanced past it:
                    // One strategy is to advance currentEnd beyond the range after issuing the last IP.
                    // For now, if nextIp is out of range, currentEnd is NOT updated.
                    // This means ipToReturn is the last valid IP. If fetchIP is called again and pool is empty,
                    // range.contains(currentEnd) will still be true for ipToReturn, but it won't advance further.
                    // This is okay if ipToReturn is not re-added to pool and range.contains(nextIp) is the true boundary.
                    logger.debug("IP {} is the last in range (next IP {} is out of range).", ipToReturn, nextIp)
                }
            }
            logger.debug("Fetched IP {} from sequence. Next sequential IP: {}", ipToReturn, currentEnd)
            return ipToReturn
        } else {
            logger.warn("IP Pool exhausted. currentEnd {} is outside the defined range [{}, {}].", currentEnd, range.startIP, range.endIP)
            return null
        }
    }

    /**
     * Releases an IP address back to the pool.
     * The IP is only added if it belongs to the same address family as the pool.
     * It does not check if the IP is within the original range or if it's a duplicate.
     *
     * @param ip The IPAddress to release.
     */
    fun release(ip: IPAddress) {
        if (ip.family != this.family) {
            logger.warn("Attempting to release IP {} of different family ({}) to pool of family {}. IP not added.", ip, ip.family, this.family)
            return
        }
        pool.add(ip)
        logger.debug("Released IP {} to pool. Pool size: {}", ip, pool.size)
    }

    /**
     * Checks if the given IP address is within the range defined for this pool.
     * Note: This does not check if the IP is currently *in* the pool (i.e., available).
     *
     * @param ip The IPAddress to check.
     * @return True if the IP is within the pool's defined range, false otherwise.
     */
    fun contains(ip: IPAddress): Boolean {
        return range.contains(ip)
    }
}
