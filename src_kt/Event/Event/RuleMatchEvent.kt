package Event.Event
import Event.EventType // Corrected import
import Messages.ConnectSession // Corrected import
import Rule.Rule // Corrected import
import IPStack.DNS.DNSSession // Corrected import
import Rule.DNSSessionMatchType // Corrected import
import Rule.DNSSessionMatchResult // Corrected import


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
