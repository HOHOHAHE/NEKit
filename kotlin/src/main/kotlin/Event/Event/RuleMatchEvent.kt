package nekit.Event.Event

import nekit.Rule.Rule
import nekit.IPStack.DNS.DNSSessionMatchResult

interface RuleMatchEvent : Event {
    class RuleMatched(val rule: Rule, val result: DNSSessionMatchResult) : RuleMatchEvent
    class RuleDidNotMatch(val rule: Rule) : RuleMatchEvent
    class DnsRuleMatched(val result: DNSSessionMatchResult) : RuleMatchEvent
}
