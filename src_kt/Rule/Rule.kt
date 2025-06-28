package Rule

// Assuming ConnectSession.kt (from Messages) and AdapterFactory.kt (placeholder from Config) are available.
// Placeholders for DNSSession, DNSSessionMatchType, DNSSessionMatchResult will be defined/refined shortly.

// --- Placeholder definitions (will be replaced by actual files from this Rule directory) ---
// Placeholder for DNSSession (ensure this is consistent with its use in other modules like IPStack/DNS)
open class DNSSession(
    // Example properties, actual definition might vary
    open var requestMessage: Any, // Placeholder for DNSMessage
    open var matchResult: DNSSessionMatchResultType? = null // Placeholder for match result
) {
    override fun toString(): String = "DNSSession(${requestMessage})"
}

// Placeholder for DNSSessionMatchType (will be an enum class)
enum class DNSSessionMatchType {
    DOMAIN, // Example value, replace with actual values from DNSSessionMatchType.swift
    IP      // Example value
}

// Placeholder for DNSSessionMatchResult (will be an enum class or sealed class)
// The .real value used in Rule.swift's base matchDNS implies this type.
enum class DNSSessionMatchResult {
    FAKE,
    REAL,
    UNKNOWN,
    PASS,
    DIRECT // Added based on possible values seen elsewhere.
}
// --- End Placeholder definitions ---


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
