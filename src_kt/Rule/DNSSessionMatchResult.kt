package com.example.nekit.Rule

/**
 * Represents the result of a DNS session matching attempt against a rule.
 */
enum class DNSSessionMatchResult {
    /**
     * The rule indicates that the DNS query should be responded to with a fake IP address.
     * This typically means the traffic should be intercepted or handled by the proxy.
     */
    FAKE,

    /**
     * The rule indicates that the DNS query should be responded to with the real IP address.
     * This typically means the traffic should be allowed to go direct.
     */
    REAL,

    /**
     * The rule cannot definitively determine the outcome based on the current information.
     * This might require further processing, like resolving the domain to an IP and re-evaluating.
     */
    UNKNOWN,

    /**
     * The rule does not match the current DNS session and processing should pass to the next rule.
     */
    PASS
}
