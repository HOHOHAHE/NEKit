package com.example.nekit.Event.Event
import com.example.nekit.Event.Event.EventType // Corrected import
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Rule.Rule
import com.example.nekit.IPStack.DNS.DNSSession
import com.example.nekit.Rule.DNSSessionMatchType
import com.example.nekit.Rule.DNSSessionMatchResult


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
