package com.example.nekit.Rule

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
// IPAddress.kt (from Utils), and IPRange.kt (from Utils) are available.

// --- Ensure DNSSession placeholder has `realIP: IPAddress?` ---
// From DNSServer.kt context, it should.
// open class DNSSession(...) {
//     open var realIP: IPAddress? = null
//     // ...
// }
// ---

/**
 * Rule that matches if the target host's IP address falls within a list of predefined IP ranges.
 *
 * @property adapterFactory The adapter factory to be used for sessions matched by this rule.
 * @property ranges The list of [IPRange] objects to match against.
 * @throws IPRangeException if any of the input range strings cannot be parsed.
 */
open class IPRangeListRule(
    private val adapterFactory: AdapterFactory,
    rangeStrings: List<String> // Input strings like "127.0.0.1/8" or "10.0.0.1+10"
) : Rule() {

    val ranges: List<IPRange>

    init {
        // Parse range strings into IPRange objects during initialization.
        // IPRange.fromString can throw IPRangeException.
        this.ranges = rangeStrings.mapNotNull { str -> // Use mapNotNull to filter out issues if desired, or map and let it throw
            try {
                IPRange.fromString(str) // Assuming IPRange.kt has this static factory method
            } catch (e: IPRangeException) {
                // Log or handle individual parsing errors, or rethrow.
                // For now, let's rethrow to match Swift's behavior of init throwing.
                // Or, collect errors and throw an aggregate, or filter out unparseable ranges.
                // To strictly match Swift, any single failure would fail the whole init.
                System.err.println("Error parsing IP range string '$str': ${e.message}")
                throw ConfigurationException.RuleParsingException("Invalid IP range string in list: '$str'. ${e.message}") // Assuming ConfigurationException exists
            }
        }
    }

    override fun toString(): String {
        return "<${this::class.simpleName ?: "IPRangeListRule"} rangeCount:${ranges.size} adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    /**
     * Matches DNS sessions based on the resolved IP address.
     * This rule only applies if the match type is IP-based and an IP address is available.
     *
     * @param session The DNS session, which should have its `realIP` property populated.
     * @param type The type of information available. Must be [DNSSessionMatchType.IP].
     * @return [DNSSessionMatchResult.UNKNOWN] if `type` is not `.IP`.
     *         [DNSSessionMatchResult.FAKE] if the `session.realIP` matches any range in the list.
     *         [DNSSessionMatchResult.PASS] if `session.realIP` is null or does not match any range.
     */
    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        if (type != DNSSessionMatchType.IP) {
            return DNSSessionMatchResult.UNKNOWN
        }

        val ipToMatch = session.realIP ?: return DNSSessionMatchResult.PASS // No IP to match against

        for (range in ranges) {
            if (range.contains(ipToMatch)) {
                // Original Swift code always returns .FAKE here, regardless of adapter type.
                return DNSSessionMatchResult.FAKE
            }
        }
        return DNSSessionMatchResult.PASS
    }

    /**
     * Matches connect sessions by checking if the session's resolved IP address
     * falls within any of the specified IP ranges.
     *
     * @param session The connect session, whose `ipAddress` property (resolved IP string) is checked.
     * @return The configured [AdapterFactory] if a match is found, otherwise null.
     *         Returns null if the session's IP address string cannot be parsed into an [IPAddress].
     */
    override fun match(session: ConnectSession): AdapterFactory? {
        // session.ipAddress is a lazy property that resolves the hostname.
        // This might return the original hostname if resolution fails, or an empty string.
        // IPAddress.parse needs a valid IP string.
        val ipToMatch = IPAddress.parse(session.ipAddress) ?: return null // Not a valid IP string, no match.

        for (range in ranges) {
            if (range.contains(ipToMatch)) {
                return adapterFactory
            }
        }
        return null
    }
}
