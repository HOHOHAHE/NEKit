// Assuming Rule.kt and all concrete rule implementations are available.
// Assuming DNSSession.kt, ConnectSession.kt, AdapterFactory.kt, Observer.kt, RuleMatchEvent.kt, ObserverFactory.kt
// and related enums (DNSSessionMatchType, DNSSessionMatchResult) are available.

// --- Refined Placeholders (ensure these are consistent with actual definitions or update them) ---

// In DNSSession.kt (placeholder or actual file)
// open class DNSSession(...) {
//     // ... other properties ...
//     open var indexToMatch: Int = 0
//     open var matchedRule: Rule? = null
//     open var ruleMatchResult: DNSSessionMatchResult? = null // Specific for rule matching outcome
//     // ...
// }

// In ConnectSession.kt (actual file in Messages)
// class ConnectSession(...) {
//     // ... other properties ...
//     var matchedRule: Rule? = null // Add this if not present
// }

// --- End Placeholders ---


/**
 * Manages a list of rules and provides methods to match DNS and connect sessions against them.
 */
open class RuleManager(inputRules: List<Rule>, appendDirect: Boolean = false) {

    val rules: List<Rule>
    var observer: Observer<RuleMatchEvent>? = null // Assuming RuleMatchEvent.kt is available

    init {
        val mutableRules = ArrayList(inputRules)
        if (appendDirect || mutableRules.isEmpty()) {
            mutableRules.add(DirectRule()) // Assuming DirectRule.kt is available
        }
        this.rules = mutableRules.toList() // Make it an immutable list internally after setup

        // Assuming ObserverFactory.kt and its currentFactory are available
        this.observer = ObserverFactory.currentFactory?.getObserverForRuleManager(this)
    }

    /**
     * Matches a DNS session against the rules, starting from `session.indexToMatch`.
     * Updates `session.matchedRule`, `session.ruleMatchResult`, and `session.indexToMatch` upon a definitive match.
     *
     * @param session The DNS session to match. Its properties will be updated based on the match.
     * @param type The type of DNS information available for matching.
     */
    fun matchDNS(session: DNSSession, type: DNSSessionMatchType) {
        // Ensure session.indexToMatch is within bounds. If it goes beyond, it means no rule matched in previous runs for this type.
        if (session.indexToMatch >= rules.size) {
            // This might happen if a previous .UNKNOWN caused a re-run and it went through all rules.
            // Or, if no rules exist beyond DirectRule which should always give a definitive answer other than PASS.
            // For now, if index is out of bounds, effectively means no more rules to check.
            return
        }

        for ((i, rule) in rules.drop(session.indexToMatch).withIndex()) {
            val actualRuleIndex = session.indexToMatch + i
            val result = rule.matchDNS(session, type)

            // Assuming DNSSession and RuleMatchEvent are correctly defined for this signal
            observer?.signal(RuleMatchEvent.DnsRuleMatched(session, rule, type, result))

            when (result) {
                DNSSessionMatchResult.FAKE,
                DNSSessionMatchResult.REAL,
                DNSSessionMatchResult.UNKNOWN -> {
                    session.matchedRule = rule
                    session.ruleMatchResult = result // Store the specific result from the rule
                    session.indexToMatch = actualRuleIndex // Update index to where this match occurred
                    return // Stop processing further rules for this DNS match cycle
                }
                DNSSessionMatchResult.PASS -> {
                    // Continue to the next rule
                }
                // else -> { /* Handle other potential results if any */ }
            }
        }

        // If loop completes, means all remaining rules resulted in PASS.
        // No specific rule matched to take definitive action (FAKE, REAL, UNKNOWN).
        // The session's matchedRule and ruleMatchResult will remain as they were (or null if no prior match).
        // The indexToMatch will effectively be rules.size, indicating all rules were checked.
        session.indexToMatch = rules.size
    }

    /**
     * Matches a connect session against the rules to find an appropriate AdapterFactory.
     *
     * @param session The connect session to match. Its `matchedRule` property may be updated.
     * @return The [AdapterFactory] from the first matching rule. Returns null if no rule matches
     *         (though this is unlikely if a `DirectRule` is appended).
     */
    fun match(session: ConnectSession): AdapterFactory? {
        // If a rule was already matched (e.g., during DNS phase that led to a fake IP now being connected)
        if (session.matchedRule != null) {
            val rule = session.matchedRule!!
            observer?.signal(RuleMatchEvent.RuleMatched(session, rule))
            // Re-evaluate this rule for the ConnectSession.
            // The original rule.match(session) might return a different adapter or null now.
            return rule.match(session)
        }

        for (rule in rules) {
            val adapterFactory = rule.match(session)
            if (adapterFactory != null) {
                observer?.signal(RuleMatchEvent.RuleMatched(session, rule))
                session.matchedRule = rule // Store the rule that matched
                return adapterFactory
            } else {
                observer?.signal(RuleMatchEvent.RuleDidNotMatch(session, rule))
            }
        }
        // This part "should never happen" if a DirectRule (which is an AllRule) is always at the end.
        // The DirectRule's match(ConnectSession) returns its DirectAdapterFactory.
        System.err.println("WARN: RuleManager: No rule matched ConnectSession ${session.toString()}, returning null adapter. This may not be intended.")
        return null
    }

    companion object {
        /**
         * The currently active global `RuleManager` instance.
         * It is initialized with an empty rule list and a `DirectRule` appended by default.
         *
         * Note: This should be set by the configuration loading process before any DNS or connect sessions are handled.
         */
        @JvmStatic // For Java interop if needed
        var currentManager: RuleManager = RuleManager(emptyList(), true)
    }
}
