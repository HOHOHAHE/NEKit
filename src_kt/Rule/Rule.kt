package com.example.nekit.Rule

import com.example.nekit.Config.ConfigurationException
import com.example.nekit.IPStack.DNS.DNSSession
import com.example.nekit.Rule.DNSSessionMatchResult
import com.example.nekit.Rule.DNSSessionMatchType
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory

abstract class Rule {
    abstract fun match(session: ConnectSession): AdapterFactory?
    abstract fun matchDNS(session: DNSSession, type: DNSSessionMatchType): DNSSessionMatchResult
}
