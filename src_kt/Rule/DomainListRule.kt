package nekit.Rule

import nekit.IPStack.DNS.DNSSession
import nekit.IPStack.Packet.DNSMessage

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.Factory.AdapterFactory
import nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory
// Assuming DNSQuery.kt placeholder (from DNSMessage.kt context) has a 'name: String' property.

// --- Ensure DNSQuery placeholder has 'name' ---
// From DNSMessage.kt context:
// class DNSQuery(val name: String, val type: DNSType, val klass: DNSClass = DNSClass.IN) { ... }
// ---




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
