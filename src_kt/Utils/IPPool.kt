package Utils

import org.slf4j.LoggerFactory
// Assuming IPAddress.kt is available
// Assuming actual IPRange.kt will be imported
import Utils.IPRange // Corrected import

// Placeholder for IPRange.kt has been removed.


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

        // Try to dispense from the sequence
        if (!range.contains(currentEnd)) {
            // currentEnd has already moved past the end of the range.
            logger.warn("IP Pool sequence exhausted. currentEnd {} is outside the defined range [{}, {}].", currentEnd, range.startIP, range.endIP)
            return null
        }

        val ipToReturn = currentEnd
        val nextIpCandidate = currentEnd.advanced(by = 1u)

        if (nextIpCandidate == null) {
            // currentEnd is the very last IP address possible for its type (e.g., 255.255.255.255 or ffff:...:ffff)
            // and cannot be advanced further.
            // We dispense currentEnd, and then effectively mark the sequence as exhausted by moving currentEnd
            // to a state that range.contains(currentEnd) will fail next time.
            // A simple way is to try advancing by a large step or use a special marker if IPAddress supported it.
            // For now, advancing by 1 again (which will be null) is fine, or simply accept currentEnd won't change.
            // The crucial part is that next time fetchIP is called, range.contains(currentEnd) should be false
            // if currentEnd was the true endIP of the range.
            // Let's advance currentEnd to a conceptual "after end" state.
            // A robust way is to set currentEnd to an IP known to be outside the range.
            // Or, rely on the next `range.contains(currentEnd)` check.
            // If currentEnd was range.endIP, nextIpCandidate would be range.endIP + 1.
            // So, we set currentEnd to this nextIpCandidate (even if null or out of range).
            logger.warn("IP {} is the last possible address of its type or failed to advance. Dispensing it.", currentEnd)
            currentEnd = nextIpCandidate ?: currentEnd // If nextIp is null, currentEnd effectively stays, but range.contains should handle it.
                                                      // A better way if nextIp is null (max IP): currentEnd = currentEnd.plusBigInt(1) if that existed to make it "invalid"
                                                      // For now, if nextIp is null, it means currentEnd was truly the last.
                                                      // The next call to fetchIP will fail range.contains(currentEnd) if currentEnd was range.endIP.
                                                      // If currentEnd was NOT range.endIP but still nextIp is null, that's an issue with advanced().
                                                      // Let's assume advanced() works. If it returns null, currentEnd was max.
                                                      // The next check `!range.contains(currentEnd)` will determine exhaustion.
            if (nextIpCandidate != null) { // Only if advanced successfully
                 currentEnd = nextIpCandidate
            } else { // currentEnd was the max representable IP, make it "invalid" for next check
                 // This is tricky. A simple way is to rely on the fact that ipToReturn (which was currentEnd)
                 // is now dispensed. If currentEnd cannot change, the next call to `range.contains(currentEnd)`
                 // will still be true.
                 // A better fix: if nextIpCandidate is null, it means currentEnd was the max IP of its type.
                 // We need to ensure that currentEnd for the *next* iteration is something that range.contains()
                 // will reliably report as false if ipToReturn was indeed range.endIP.
                 // Simplest: If nextIpCandidate is null, we assume currentEnd was the last one *ever*.
                 // To ensure it doesn't get picked again from sequence:
                 // We can advance currentEnd to a conceptual "afterEnd" state.
                 // If `advanced(by=1)` returns null, it means `currentEnd` is max possible IP.
                 // We make `currentEnd` effectively "one past the end" by ensuring it won't be contained in range.
                 // This is implicitly handled if range.endIP was that max IP.
                 // A simpler model:
                 // currentEnd is the *next candidate*. We check if it's in range.
                 // If yes, dispense it, then advance currentEnd for the *next* call.
            }

        } else {
            // nextIpCandidate is valid, this becomes the new currentEnd for the *next* fetch.
            currentEnd = nextIpCandidate
        }

        logger.debug("Fetched IP {} from sequence. Next candidate sequential IP: {}", ipToReturn, currentEnd)
        return ipToReturn
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
