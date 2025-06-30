package com.example.nekit.Rule

import com.example.nekit.Config.ConfigurationException
import com.example.nekit.IPStack.DNS.DNSSession
import com.example.nekit.Rule.DNSSessionMatchResult
import com.example.nekit.Rule.DNSSessionMatchType
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.Factory.AdapterFactory
import com.example.nekit.Rule.RuleManager

class RuleManager(val rules: List<Rule>, val appendDirect: Boolean) {
    fun ruleMatchResult(): Any? = null // Placeholder
}