package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers

/**
 * 共享的網路調度器，避免為每個連線建立獨立的 SelectorManager
 * 這樣可以大幅減少執行緒數量，提升高併發場景下的效能
 */
object NetworkDispatchers {
    /**
     * 全域共享的 SelectorManager
     * 所有 Socket 連線都會使用這個共享的實例，而不是各自建立
     */
    val selectorManager = ActorSelectorManager(Dispatchers.IO)
}