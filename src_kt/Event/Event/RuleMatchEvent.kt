package com.example.nekit.Event.Event

import com.example.nekit.Rule.Rule
import com.example.nekit.IPStack.DNS.DNSSessionMatchResult

interface RuleMatchEvent : Event {
    class RuleMatched(val rule: Rule, val result: DNSSessionMatchResult) : RuleMatchEvent
    class RuleDidNotMatch(val rule: Rule) : RuleMatchEvent
    class DnsRuleMatched(val result: DNSSessionMatchResult) : RuleMatchEvent
}
