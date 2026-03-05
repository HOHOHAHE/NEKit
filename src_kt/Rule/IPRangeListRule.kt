package nekit.Rule

import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.Factory.AdapterFactory
import nekit.Utils.IPAddress
import nekit.Utils.IPRange
import nekit.IPStack.DNS.DNSSession
import nekit.Rule.DNSSessionMatchType
import nekit.Rule.DNSSessionMatchResult

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
