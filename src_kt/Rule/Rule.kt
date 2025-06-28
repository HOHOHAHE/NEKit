package Rule

import Messages.ConnectSession // Corrected import
import Socket.AdapterSocket.Factory.AdapterFactory // Corrected import
import IPStack.DNS.DNSSession // Corrected import
import Rule.DNSSessionMatchType // Corrected import
import Rule.DNSSessionMatchResult // Corrected import


/**
 * Base class for rules that define actions for DNS requests and connect sessions.
 * Subclasses should override [matchDNS] and/or [match] to implement specific rule logic.
 */
open class Rule {

    /**
     * Default constructor.
     */
    constructor()

    /**
     * Provides a string representation of the rule.
     * Subclasses might override this for more specific descriptions.
     */
    override fun toString(): String {
        return "<${this::class.simpleName ?: "Rule"}>" // Use actual class name if available
    }

    /**
     * Matches a DNS request against this rule.
     * The base implementation always returns [DNSSessionMatchResult.REAL].
     *
     * @param session The DNS session to match.
     * @param type The type of information available for matching (e.g., domain name, IP address).
     * @return The result of the match.
     */
    open fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        return DNSSessionMatchResult.REAL // Base behavior: consider it a real request, no specific action by this rule.
    }

    /**
     * Matches a connection session against this rule.
     * The base implementation never matches (returns null).
     *
     * @param session The connect session to match.
     * @return The [AdapterFactory] to be used if the rule matches, or null if it does not match.
     */
    open fun match(session: ConnectSession): AdapterFactory? {
        return null // Base behavior: rule does not match.
    }
}
