package com.example.nekit.Rule

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import com.example.nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory

// --- Ensure DNSSession placeholder has `realIP: IPAddress?` ---
// From DNSServer.kt context, it should.
// open class DNSSession(...) {
//     open var realIP: IPAddress? = null
//     // ...
// }
// ---

/**
 * Rule that matches if a DNS resolution has failed for the session.
 *
 * @property adapterFactory The adapter factory to be used if this rule matches.
 */
open class DNSFailRule(
    private val adapterFactory: AdapterFactory
) : Rule() {

    override fun toString(): String {
        return "<${this::class.simpleName ?: "DNSFailRule"} adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    /**
     * Matches a DNS session. This rule applies if the match type is IP-based
     * and the session does not have a resolved real IP address (`session.realIP == null`).
     *
     * @param session The DNS session, expected to have its `realIP` property set (or null if resolution failed).
     * @param type The type of information available. Must be [DNSSessionMatchType.IP].
     * @return
     *         - [DNSSessionMatchResult.UNKNOWN] if `type` is not `.IP`.
     *         - [DNSSessionMatchResult.REAL] or [DNSSessionMatchResult.FAKE] (based on adapter type) if DNS resolution failed (`session.realIP == null`).
     *         - [DNSSessionMatchResult.PASS] if DNS resolution succeeded (`session.realIP != null`).
     */
    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        if (type != DNSSessionMatchType.IP) {
            return DNSSessionMatchResult.UNKNOWN
        }

        return if (session.realIP == null) { // DNS resolution failed for this session
            if (adapterFactory is DirectAdapterFactory) {
                DNSSessionMatchResult.REAL
            } else {
                DNSSessionMatchResult.FAKE
            }
        } else {
            DNSSessionMatchResult.PASS // DNS resolution succeeded, so this rule does not apply
        }
    }

    /**
     * Matches a connect session. This rule applies if the session's `ipAddress` property is an empty string,
     * which is taken to indicate a failure in resolving the host to an IP address.
     *
     * TODO: Verify that `ConnectSession.ipAddress` indeed returns an empty string on DNS resolution failure.
     *       If it returns the original hostname or throws an exception handled elsewhere, this matching condition
     *       might need adjustment. The current `Utils.DNS.resolve` placeholder returns the hostname on failure.
     *
     * @param session The connect session, whose `ipAddress` property is checked.
     * @return The configured [AdapterFactory] if DNS resolution failed (ipAddress is empty), otherwise null.
     */
    override fun match(session: ConnectSession): AdapterFactory? {
        // The ConnectSession.ipAddress is a lazy val. Accessing it here will trigger resolution if not already done.
        // This rule matches if that resolution effectively results in an empty string.
        return if (session.ipAddress.isEmpty()) {
            adapterFactory
        } else {
            null
        }
    }
}
