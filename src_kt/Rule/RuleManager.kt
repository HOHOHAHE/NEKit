package nekit.Rule

import nekit.Config.ConfigurationException
import nekit.IPStack.DNS.DNSSession
import nekit.Rule.DNSSessionMatchResult
import nekit.Rule.DNSSessionMatchType
import nekit.Messages.ConnectSession
import nekit.Socket.AdapterSocket.Factory.AdapterFactory
import nekit.Socket.AdapterSocket.Factory.DirectAdapterFactory
import nekit.Rule.RuleManager

class RuleManager(val rules: List<Rule>, val appendDirect: Boolean) {
    fun match(session: ConnectSession): AdapterFactory {
        for (rule in rules) {
            val result = rule.match(session)
            if (result != null) {
                return result
            }
        }
        return DirectAdapterFactory()
    }

    companion object {
        var currentManager: RuleManager? = null
    }
}