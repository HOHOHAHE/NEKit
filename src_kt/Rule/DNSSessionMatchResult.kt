package com.example.nekit.Rule

/**
 * Represents the result of matching a DNS request against a rule.
 */
enum class DNSSessionMatchResult {
    /**
     * The request matches the rule, and the connection should proceed with a real IP address.
     * This might involve DNS resolution if not already done.
     */
    REAL,

    /**
     * The request matches the rule, and a "fake" IP address should be used or generated.
     * This is typically used in scenarios where DNS manipulation is performed for routing or filtering.
     * The system will need to track this fake IP to handle subsequent connections.
     */
    FAKE,

    /**
     * The rule cannot determine a match based on the current information (e.g., only domain is available,
     * but the rule requires the resolved IP address). The matching process might need to re-evaluate
     * this rule after more information (like the resolved IP) is obtained.
     */
    UNKNOWN,

    /**
     * The rule does not match the DNS request. The rule engine should proceed to the next rule.
     */
    PASS;
}
