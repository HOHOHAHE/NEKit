package com.example.nekit.Rule

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.IPRange
import com.example.nekit.IPStack.DNS.DNSSession
import com.example.nekit.IPStack.DNS.DNSSessionMatchType
import com.example.nekit.IPStack.DNS.DNSSessionMatchResult

class IPRangeListRule(
    private val ranges: List<IPRange>,
    private val adapterFactory: AdapterFactory
) : Rule() {

    override fun match(session: ConnectSession): AdapterFactory? {
        val ip = IPAddress.parse(session.host)
        if (ip != null) {
            for (range in ranges) {
                if (range.contains(ip)) {
                    return adapterFactory
                }
            }
        }
        return null
    }

    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        // This rule does not apply to DNS requests.
        return DNSSessionMatchResult.PASS
    }
}
