package com.example.nekit.RawSocket

import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

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
    
    /**
     * 專門處理 Android Native Blocking Sockets (RawCellularTCPSocket, RawCellularUDPSocket) 的高併發調度器。
     * 由於 java.net.Socket.getInputStream().read() 以及 DatagramSocket.receive() 是阻塞的，
     * 如果使用 Dispatchers.IO (預設上限 64 執行緒)，當有超過 64 個連線時，會導致整個 App 的 IO 協程（包含 DB / Ktor）被死鎖。
     * CachedThreadPool 會在需要時動態建立執行緒，並在閒置 60 秒後自動回收。
     */
    val CellularBlockingIO = Executors.newCachedThreadPool().asCoroutineDispatcher()
}