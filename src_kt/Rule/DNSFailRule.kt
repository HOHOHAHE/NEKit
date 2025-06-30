package com.example.nekit.Rule

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import com.example.nekit.Utils.IPAddress
import com.example.nekit.IPStack.DNS.DNSSession
import com.example.nekit.Rule.DNSSessionMatchType
import com.example.nekit.Rule.DNSSessionMatchResult

class DNSFailRule(
    private val adapterFactory: AdapterFactory
) : Rule() {
    override fun match(session: ConnectSession): AdapterFactory? {
        val ip = IPAddress.parse(session.host)
        return if (ip != null && (ip.isIPv4 || ip.isIPv6)) {
            adapterFactory
        } else {
            null
        }
    }

    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        // This rule does not apply to DNS requests.
        return DNSSessionMatchResult.PASS
    }
}
