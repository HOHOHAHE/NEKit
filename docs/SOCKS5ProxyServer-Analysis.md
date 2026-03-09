# SOCKS5ProxyServer 架構分析

## 類別繼承關係

```mermaid
classDiagram
    class ProxyServer {
        +address: IPAddress?
        +port: Port
        +observer: Observer
        #tunnels: MutableList~Tunnel~
        +start() suspend
        +stop() suspend
        +didAcceptNewSocket(socket) suspend
        +tunnelDidClose(tunnel)
    }
    class TCPProxyServer {
        -serverSocket: ServerSocket?
        -selectorManager: SelectorManager?
        -serverJob: Job?
        +start() suspend
        +stop() suspend
        -handleKtorClientConnection(socket) suspend
        #handleNewAcceptedSocket(socket)
    }
    class SOCKS5ProxyServer {
        +outboundInterfaceType: NetworkInterfaceType
        +handleNewAcceptedSocket(socket)
    }
    ProxyServer <|-- TCPProxyServer
    TCPProxyServer <|-- SOCKS5ProxyServer
```

---

## 主流程：TCP 連線建立與 Tunnel 開啟

```mermaid
sequenceDiagram
    participant Client as SOCKS5 Client
    participant TCPServer as TCPProxyServer
    participant SOCKS5Server as SOCKS5ProxyServer
    participant ProxySrv as ProxyServer
    participant SOCKS5Sock as SOCKS5ProxySocket
    participant Tunnel as Tunnel

    Note over TCPServer, ProxySrv: 啟動階段
    SOCKS5Server->>TCPServer: start()
    TCPServer->>TCPServer: aSocket().tcp().bind(ip, port)
    TCPServer->>ProxySrv: super.start()
    ProxySrv->>ProxySrv: observer.signal(Started)
    TCPServer->>TCPServer: GlobalScope.launch(Dispatchers.IO) 開始 accept loop

    Note over Client, Tunnel: 連線處理階段
    Client->>TCPServer: TCP 連線請求
    TCPServer->>TCPServer: serverSocket.accept() → AcceptedRawTCPSocket
    TCPServer->>SOCKS5Server: handleNewAcceptedSocket(rawSocket)
    SOCKS5Server->>SOCKS5Sock: SOCKS5ProxySocket(rawSocket)
    SOCKS5Server->>SOCKS5Server: CoroutineScope(Dispatchers.Default).launch
    SOCKS5Server->>ProxySrv: super.didAcceptNewSocket(socks5Sock)
    ProxySrv->>Tunnel: Tunnel(socket) → openTunnel()
    Tunnel->>SOCKS5Sock: openSocket() → 開始 SOCKS5 握手
```

---

## SOCKS5 握手狀態機

```mermaid
stateDiagram-v2
    [*] --> GREETING : openSocket(), readDataTo(2)
    GREETING --> READING_METHODS : 驗證版本=0x05, readDataTo(nMethods)
    GREETING --> [*] : 版本錯或資料不足 → forceDisconnect

    READING_METHODS --> CONNECTING : 回覆 [0x05,0x00] NO AUTH, readDataTo(4)

    CONNECTING --> READING_IPV4 : ATYP=0x01
    CONNECTING --> READING_DOMAIN_LENGTH : ATYP=0x03
    CONNECTING --> READING_IPV6 : ATYP=0x04
    CONNECTING --> [*] : 不支援的 CMD/ATYP → forceDisconnect

    READING_IPV4 --> READING_PORT : 解析 IP, readDataTo(2)
    READING_IPV6 --> READING_PORT : 解析 IP, readDataTo(2)
    READING_DOMAIN_LENGTH --> READING_DOMAIN : readDataTo(len)
    READING_DOMAIN --> READING_PORT : readDataTo(2)

    READING_PORT --> SENDING_RESPONSE : 解析 port → 依 CMD 分流
    SENDING_RESPONSE --> FORWARDING : didWrite() 觸發, status=ESTABLISHED
    FORWARDING --> FORWARDING : 雙向資料轉發
    FORWARDING --> [*] : forceDisconnect
```

---

## CMD 分流：TCP CONNECT vs UDP ASSOCIATE

```mermaid
flowchart TD
    A[handlePort 解析 host + port] --> B{isUdpAssociate?}
    B -- "否 CMD=0x01" --> C[delegate.didReceive ConnectSession]
    C --> D[Tunnel 選擇 Adapter 建立出口連線]
    D --> E[adapter.respondTo → 回覆成功]
    E --> F[進入 FORWARDING 雙向轉發]

    B -- "是 CMD=0x03" --> G[startUdpRelay clientIP, clientPort]
    G --> H[SOCKS5UDPRelayServer.start]
    H --> I{啟動成功?}
    I -- 是 --> J[sendUdpAssociateSuccessResponse\n回傳 relay 的 IP:Port]
    I -- 否 --> K[回覆 0x01 Failure → forceDisconnect]
```

---

## 關閉 / 錯誤處理

```mermaid
flowchart LR
    A[forceDisconnect] --> B{udpRelayServer != null?}
    B -- 是 --> C[udpRelayServer.stop]
    C --> D[super.forceDisconnect]
    B -- 否 --> D

    E[coroutine catch Exception] --> F[socket.forceDisconnect 關閉 raw socket]

    G[Tunnel 關閉] --> H[observer.signal TunnelClosed]
    H --> I[tunnels.remove tunnel]
```

---

## 各層職責對照

| 層次 | 類別 | 職責 |
|------|------|------|
| 基礎層 | `ProxyServer` | Tunnel 列表管理、生命週期、Observer 通知 |
| TCP 層 | `TCPProxyServer` | Ktor ServerSocket 綁定、accept loop |
| SOCKS5 協議層 | `SOCKS5ProxyServer` | 包裝成 `SOCKS5ProxySocket`，啟動 coroutine |
| 握手層 | `SOCKS5ProxySocket` | 狀態機驅動 SOCKS5 握手、TCP/UDP 分流 |
| UDP 層 | `SOCKS5UDPRelayServer` | UDP ASSOCIATE 封包轉發 |

---

## 架構問題與改進建議

### ✅ 設計做得好的地方

- 繼承層次清楚，職責分離明確
- SOCKS5 狀態機完整覆蓋協議流程
- `tunnels` list 使用 `Mutex` 保護，避免 race condition
- UDP Relay 使用單一統一 outbound socket，避免 `Dispatchers.IO` 耗盡

---

### ⚠️ 需要改進的問題

#### 🔴 高優先：Coroutine Scope 洩漏

```kotlin
// SOCKS5ProxyServer.kt
val scope = CoroutineScope(Dispatchers.Default)  // 孤兒 scope，無人持有
scope.launch { ... }
```

每次新連線都建立一個孤兒 scope，無法在連線關閉時 cancel，導致資源洩漏。

**建議**：改用 server 層級的 `SupervisorJob` scope：

```kotlin
// 在 SOCKS5ProxyServer 中宣告
private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

override fun handleNewAcceptedSocket(socket: RawTCPSocketProtocol) {
    val socks5ProxySocket = SOCKS5ProxySocket(socket)
    socks5ProxySocket.outboundInterfaceType = this.outboundInterfaceType
    serverScope.launch {
        try {
            super.didAcceptNewSocket(socks5ProxySocket)
        } catch (e: Exception) { ... }
    }
}

// 在 stop() 時取消
override suspend fun stop() {
    serverScope.cancel()
    super.stop()
}
```

---

#### 🟡 中優先：GlobalScope 濫用

```kotlin
// SOCKS5ProxySocket.kt & TCPProxyServer.kt
GlobalScope.launch(...)
```

`GlobalScope` 讓 coroutine 生命週期脫離結構化管理，難以追蹤和取消。

**建議**：注入或傳入有明確生命週期的 scope。

---

#### 🟡 中優先：`didBecomeReadyToForward` 雙重觸發

```kotlin
// ProxySocket.kt L103 - super.respondTo() 已呼叫
delegate?.get()?.didBecomeReadyToForward(this)

// SOCKS5ProxySocket.kt L93 - didWrite 的 SENDING_RESPONSE 又呼叫一次
delegate?.get()?.didBecomeReadyToForward(this)
```

**建議**：確認 `SOCKS5ProxySocket.respondTo` 覆寫後是否需要呼叫 `super`。若不呼叫 `super.respondTo()`，則去掉第一次觸發；若呼叫，則移除 `didWrite` 裡的第二次觸發。

---

#### 🟡 中優先：UDP 客戶端辨識條件 Operator Precedence Bug

```kotlin
// SOCKS5UDPRelayServer.kt L121-122
expectedClientAddress.presentation == "127.0.0.1" && sourceAddress.presentation == "::1"
// 上面這段 && 優先於 ||，可能造成非預期的條件組合
```

**建議**：加上顯式括號：

```kotlin
(expectedClientAddress.presentation == "127.0.0.1" && sourceAddress.presentation == "::1")
```

---

#### 🟠 低優先：Accept Loop 出錯後不重啟

```kotlin
// TCPProxyServer.kt
} catch (e: Exception) {
    if (e !is CancellationException) {
        logger.error(...)
        // ← loop 結束，整個 server 停止接受新連線
    }
}
```

任何非取消的異常都會永久終止 accept loop。

**建議**：加入 retry / 重啟機制：

```kotlin
while (isActive) {
    try {
        val clientSocket = serverSocket!!.accept()
        launch { handleKtorClientConnection(clientSocket) }
    } catch (e: CancellationException) {
        break
    } catch (e: Exception) {
        logger.error("Accept error, retrying: {}", e.message)
        delay(500) // 避免 tight loop
    }
}
```

---

### 優先度總覽

| 問題 | 影響 | 優先度 |
|---|---|---|
| 孤兒 CoroutineScope 洩漏 | 記憶體/資源洩漏 | 🔴 高 |
| GlobalScope 濫用 | 生命週期不可控 | 🟡 中 |
| `didBecomeReadyToForward` 雙重觸發 | 邏輯 bug | 🟡 中 |
| UDP 客戶端辨識邏輯 operator precedence | 潛在 bug | 🟡 中 |
| Accept Loop 不重啟 | server 單點故障 | 🟠 低-中 |
