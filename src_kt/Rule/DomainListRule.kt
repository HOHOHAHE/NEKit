// Assuming Rule.kt, DNSSession.kt, DNSSessionMatchType.kt, DNSSessionMatchResult.kt are available.
import Messages.ConnectSession // Corrected import
import Socket.AdapterSocket.Factory.AdapterFactory // Corrected import
import Socket.AdapterSocket.Factory.DirectAdapterFactory // Corrected import
// Assuming DNSQuery.kt placeholder (from DNSMessage.kt context) has a 'name: String' property.

// --- Ensure DNSQuery placeholder has 'name' ---
// From DNSMessage.kt context:
// class DNSQuery(val name: String, val type: DNSType, val klass: DNSClass = DNSClass.IN) { ... }
// ---

/**
 * Criteria for matching a domain string.
 */
sealed class MatchCriterion {
    /**
     * Abstract method to check if the given domain string matches this criterion.
     * @param domain The domain string to test.
     * @return True if the domain matches, false otherwise.
     */
    abstract fun matches(domain: String): Boolean

    /**
     * Matches the domain against a regular expression.
     * @property regex The Kotlin Regex object.
     */
    data class RegexCriterion(val regex: Regex) : MatchCriterion() {
        override fun matches(domain: String): Boolean = regex.containsMatchIn(domain)
    }

    /**
     * Matches if the domain starts with the given prefix.
     * @property prefix The prefix string. Case sensitivity depends on usage.
     *                  For domain matching, typically case-insensitive. Consider toLowerCasing both if needed.
     */
    data class PrefixCriterion(val prefix: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.startsWith(prefix, ignoreCase)
    }

    /**
     * Matches if the domain ends with the given suffix.
     * @property suffix The suffix string. Case sensitivity depends on usage.
     */
    data class SuffixCriterion(val suffix: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.endsWith(suffix, ignoreCase)
    }

    /**
     * Matches if the domain contains the given keyword.
     * @property keyword The keyword string. Case sensitivity depends on usage.
     */
    data class KeywordCriterion(val keyword: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.contains(keyword, ignoreCase)
    }

    /**
     * Matches if the domain is an exact match to the given string.
     * @property matchString The string to match completely. Case sensitivity depends on usage.
     */
    data class CompleteCriterion(val matchString: String, val ignoreCase: Boolean = true) : MatchCriterion() {
        override fun matches(domain: String): Boolean = domain.equals(matchString, ignoreCase)
    }
}


/**
 * Rule that matches a host domain against a list of predefined criteria.
 *
 * @property adapterFactory The adapter factory to be used for sessions matched by this rule.
 * @property matchCriteria The list of criteria to check against the domain.
 */
open class DomainListRule(
    private val adapterFactory: AdapterFactory,
    val matchCriteria: List<MatchCriterion> = emptyList() // Made val, initialized in constructor
) : Rule() {

    override fun toString(): String {
        return "<${this::class.simpleName ?: "DomainListRule"} criteriaCount:${matchCriteria.size} adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    /**
     * Matches DNS sessions by checking the query domain name against the match criteria.
     *
     * @param session The DNS session, containing the query name.
     * @param type The type of information available (not directly used by this rule's domain matching).
     * @return [DNSSessionMatchResult.REAL] or [DNSSessionMatchResult.FAKE] if a match is found
     *         (depending on adapter type), otherwise [DNSSessionMatchResult.PASS].
     */
    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        // Assuming DNSSession.requestMessage is DNSMessage, and DNSMessage.queries is List<DNSQuery>
        // And DNSQuery has a 'name' property.
        val domainToMatch = (session.requestMessage as? DNSMessage)?.queries?.firstOrNull()?.name

        if (domainToMatch != null && matchDomain(domainToMatch)) {
            return if (adapterFactory is DirectAdapterFactory) {
                DNSSessionMatchResult.REAL
            } else {
                DNSSessionMatchResult.FAKE
            }
        }
        return DNSSessionMatchResult.PASS
    }

    /**
     * Matches connect sessions by checking the session's host against the match criteria.
     *
     * @param session The connect session, containing the host to connect to.
     * @return The configured [AdapterFactory] if a match is found, otherwise null.
     */
    override fun match(session: ConnectSession): AdapterFactory? {
        if (matchDomain(session.host)) { // session.host is the (potentially resolved) hostname
            return adapterFactory
        }
        return null
    }

    /**
     * Checks if the given domain string matches any of the criteria in this rule.
     *
     * @param domain The domain string to check.
     * @return True if any criterion matches the domain, false otherwise.
     */
    private fun matchDomain(domain: String): Boolean {
        // Domain matching is typically case-insensitive.
        // The MatchCriterion data classes now handle ignoreCase, defaulting to true.
        val domainToCompare = domain // Potentially domain.lowercase() if criteria don't handle case.
        for (criterion in matchCriteria) {
            if (criterion.matches(domainToCompare)) {
                return true
            }
        }
        return false
    }
}
