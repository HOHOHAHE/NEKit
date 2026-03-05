package nekit.Rule

import nekit.Config.ConfigurationException
import nekit.IPStack.DNS.DNSSession
import nekit.Rule.DNSSessionMatchResult
import nekit.Rule.DNSSessionMatchType
import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.Factory.AdapterFactory

abstract class Rule {
    abstract fun match(session: ConnectSession): AdapterFactory?
    abstract fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult
}
