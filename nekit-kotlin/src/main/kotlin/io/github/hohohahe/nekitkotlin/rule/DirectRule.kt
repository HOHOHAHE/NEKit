package io.github.hohohahe.nekitkotlin.rule

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory

class DirectRule(private val adapterFactory: AdapterFactory) : Rule {
    override fun match(session: ConnectSession): AdapterFactory? {
        return adapterFactory
    }
}
