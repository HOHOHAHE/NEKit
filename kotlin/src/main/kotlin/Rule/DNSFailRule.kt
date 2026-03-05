package nekit.Rule

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.Factory.AdapterFactory
import nekit.Utils.IPAddress
import nekit.IPStack.DNS.DNSSession
import nekit.Rule.DNSSessionMatchType
import nekit.Rule.DNSSessionMatchResult

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
