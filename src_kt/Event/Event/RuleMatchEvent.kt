// Assuming EventType.kt is in this package or imported.
// Assuming ConnectSession and other necessary types are available (possibly as placeholders).

// --- Placeholders for external types ---
// class ConnectSession { override fun toString(): String = "ConnectSession(${hashCode()})" } // Already used

interface Rule { // New placeholder
    // Define common properties/methods for Rule if any, or just use as an opaque type.
    // toString() is good for debugging in events.
    override fun toString(): String // Make it an interface that requires toString
}

class DNSSession { // New placeholder
    override fun toString(): String = "DNSSession(${hashCode()})"
}

enum class DNSSessionMatchType { // New placeholder enum
    // Example values, replace with actual values from source if different
    A_RECORD,
    CNAME_RECORD,
    MX_RECORD,
    TXT_RECORD,
    UNKNOWN;

    override fun toString(): String = name // Default is fine, or customize
}

enum class DNSSessionMatchResult { // New placeholder enum
    // Example values
    MATCHED_ALLOW,
    MATCHED_DENY,
    MATCHED_DIRECT,
    NO_MATCH,
    FAILED;

    override fun toString(): String = name // Default is fine, or customize
}
// --- End Placeholders ---


sealed class RuleMatchEvent : EventType {
    data class RuleMatched(val session: ConnectSession, val rule: Rule) : RuleMatchEvent()
    data class RuleDidNotMatch(val session: ConnectSession, val rule: Rule) : RuleMatchEvent()
    data class DnsRuleMatched(
        val session: DNSSession,
        val rule: Rule,
        val type: DNSSessionMatchType,
        val result: DNSSessionMatchResult
    ) : RuleMatchEvent()

    override fun toString(): String {
        return when (this) {
            is RuleMatched -> "Rule $rule matched session $session."
            is RuleDidNotMatch -> "Rule $rule did not match session $session."
            is DnsRuleMatched -> "Rule $rule matched DNS session $session of type $type, the result is $result."
        }
    }
}
