// Assuming Rule.kt, DNSSessionMatchType.kt, DNSSessionMatchResult.kt are available in this package.
// Assuming ConnectSession.kt (from Messages) and AdapterFactory.kt, DirectAdapterFactory.kt (placeholders from Config) are available.
// Assuming DNSSession.kt placeholder will be updated or defined to include countryCode.

// --- Refined Placeholder for DNSSession if not already updated ---
// This is a more complete placeholder based on CountryRule's needs.
// Ideally, DNSSession.kt would be the single source of truth.
/*
open class DNSSession(
    open var requestMessage: Any, // Placeholder for DNSMessage
    open var matchResult: DNSSessionMatchResultType? = null, // From DNSServer.kt context
    open var countryCode: String? = null // Country code of the resolved IP for the query
) {
    override fun toString(): String = "DNSSession(${requestMessage}, country=$countryCode)"
}
*/
// --- End Refined Placeholder for DNSSession ---


/**
 * Rule that matches based on the geographical location (country code) of the session's destination IP address.
 *
 * @property countryCode The ISO country code to match against (e.g., "US", "CN").
 * @property match If true, the rule matches if the session's country IS the `countryCode`.
 *                 If false, the rule matches if the session's country IS NOT the `countryCode`.
 * @property adapterFactory The adapter factory to be used for sessions matched by this rule.
 */
open class CountryRule(
    val countryCode: String,
    val match: Boolean,
    private val adapterFactory: AdapterFactory
) : Rule() {

    override fun toString(): String {
        return "<${this::class.simpleName ?: "CountryRule"} countryCode:$countryCode match:$match adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    /**
     * Matches DNS sessions based on the country of the resolved IP address.
     * This rule only applies if the match type is `.IP` (i.e., IP address is available).
     *
     * @param session The DNS session, which should have its `countryCode` property populated
     *                (e.g., after a GeoIP lookup on the resolved IP).
     * @param type The type of information available for matching. Must be `.IP`.
     * @return The DNS match result:
     *         - `.UNKNOWN` if `type` is not `.IP`.
     *         - `.REAL` or `.FAKE` (based on adapter type) if the country condition is met.
     *         - `.PASS` if the country condition is not met.
     */
    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        if (type != DNSSessionMatchType.IP) {
            return DNSSessionMatchResult.UNKNOWN
        }

        // session.countryCode should be populated by the DNS processing flow after GeoIP lookup.
        val sessionCountry = session.countryCode // Assuming DNSSession has this property (placeholder updated)

        // Logic: (sessionCountry == this.countryCode) == this.match
        // If match is true: rule applies if sessionCountry IS this.countryCode
        // If match is false: rule applies if sessionCountry IS NOT this.countryCode
        val countryConditionMet = (sessionCountry == this.countryCode) == this.match

        return if (countryConditionMet) {
            if (adapterFactory is DirectAdapterFactory) {
                DNSSessionMatchResult.REAL
            } else {
                DNSSessionMatchResult.FAKE
            }
        } else {
            DNSSessionMatchResult.PASS
        }
    }

    /**
     * Matches connect sessions based on the country of the destination.
     * The country is determined by the `session.country` property (which internally uses GeoIP).
     *
     * @param session The connect session to match.
     * @return The configured [AdapterFactory] if the country condition is met, otherwise null.
     */
    override fun match(session: ConnectSession): AdapterFactory? {
        // session.country is a lazy property that performs GeoIP lookup.
        val sessionCountry = session.country // This might be an empty string if lookup fails.
                                           // Consider how to handle empty sessionCountry vs. a specific countryCode.
                                           // If countryCode is also "", then ""=="" is true.

        // Logic: (sessionCountry == this.countryCode) == this.match
        val countryConditionMet = (sessionCountry.equals(this.countryCode, ignoreCase = true)) == this.match
        // Using equalsIgnoreCase for robustness, as country codes are typically case-insensitive in practice,
        // though ISO standard is uppercase. ConnectSession.country might return mixed case from some GeoIP libs.

        return if (countryConditionMet) {
            adapterFactory
        } else {
            null
        }
    }
}
