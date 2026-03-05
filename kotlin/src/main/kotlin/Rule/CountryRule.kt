package nekit.Rule

import nekit.Messages.ConnectSession
import nekit.IPStack.DNS.DNSSession
import nekit.Rule.DNSSessionMatchType
import nekit.Rule.DNSSessionMatchResult
import nekit.Socket.AdapterSocket.Factory.AdapterFactory
import nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory
import nekit.GeoIP.GeoIP
import nekit.Utils.IPAddress

open class CountryRule(
    private val countryCode: String,
    private val match: Boolean,
    private val adapterFactory: AdapterFactory
) : Rule() {

    override fun toString(): String {
        return "<${this::class.simpleName ?: "CountryRule"} countryCode:$countryCode match:$match adapter:${adapterFactory::class.simpleName ?: adapterFactory}>"
    }

    override fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult {
        if (type != DNSSessionMatchType.IP) {
            return DNSSessionMatchResult.UNKNOWN
        }

        val sessionCountry = session.realIP?.toString()?.let { GeoIP.lookUp(it) } ?: ""
        val countryConditionMet = (sessionCountry.equals(this.countryCode, ignoreCase = true)) == this.match

        return if (countryConditionMet) {
            if (adapterFactory is DirectAdapterFactory) {
                DNSSessionMatchResult.REAL
            } else {
                DNSSessionMatchResult.FAKE
            }
        } else {
            DNSSessionMatchResult.PASS
        }
    }

    override fun match(session: ConnectSession): AdapterFactory? {
        val ip = IPAddress.parse(session.host)
        val sessionCountry = ip?.toString()?.let { GeoIP.lookUp(it) } ?: ""

        val countryConditionMet = (sessionCountry.equals(this.countryCode, ignoreCase = true)) == this.match

        return if (countryConditionMet) {
            adapterFactory
        } else {
            null
        }
    }
}
