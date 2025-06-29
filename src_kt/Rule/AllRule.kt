package com.example.nekit.Rule

// Assuming Rule.kt, DNSSession.kt, DNSSessionMatchType.kt, DNSSessionMatchResult.kt are available in this package.
// Assuming ConnectSession.kt (from Messages) and AdapterFactory.kt, DirectAdapterFactory.kt (placeholders from Config) are available.

import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import com.example.nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory

/**
 * Rule that matches all DNS and connect sessions, applying a specified adapter factory.
 *
 * @property adapterFactory The adapter factory to be used for sessions matched by this rule.
 */
open class AllRule(
    private val adapterFactory: AdapterFactory
) : Rule() { // Calls super() implicitly

    /**
     * Provides a string representation of the AllRule.
     */
    override fun toString(): String {
        // Using the class name dynamically is often better for maintainability if class is renamed.
        return "<${this::class.simpleName ?: "AllRule"} adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    /**
     * Matches DNS sessions.
     * Returns [DNSSessionMatchResult.REAL] if the associated adapter is a [DirectAdapterFactory],
     * otherwise returns [DNSSessionMatchResult.FAKE].
     *
     * @param session The DNS session to match.
     * @param type The type of information available for matching.
     * @return The result of the DNS match.
     */
    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        // "only return real IP when we connect to remote directly" - Swift comment
        return if (adapterFactory is DirectAdapterFactory) {
            DNSSessionMatchResult.REAL
        } else {
            DNSSessionMatchResult.FAKE
        }
    }

    /**
     * Matches connect sessions.
     * This rule always matches and returns its configured [AdapterFactory].
     *
     * @param session The connect session to match.
     * @return The configured [AdapterFactory].
     */
    override fun match(session: ConnectSession): AdapterFactory? {
        return adapterFactory
    }
}
