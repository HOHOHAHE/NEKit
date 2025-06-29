package com.example.nekit.Rule

/**
 * Specifies the type of information available for DNS session matching.
 * This helps determine the stage of matching, as IP information might only be
 * available after an initial DNS resolution.
 */
enum class DNSSessionMatchType {
    /**
     * Indicates that only domain name information is available for matching.
     * This is typically used for an initial match attempt before any DNS resolution.
     */
    DOMAIN,

    /**
     * Indicates that the IP address (resolved from the domain name) is available for matching.
     * This is used when a rule needs to re-evaluate based on the resolved IP address,
     * often after an initial domain-based match returned an "unknown" or "needs IP" result.
     */
    IP;
}
