# iOS SOCKS5 UDP Relay 偵錯報告

**撰寫日期**：2026-03-11  
**狀態**：✅ 已解決

---

## 背景

本專案使用 NEKit 在 iOS Network Extension 中實作 SOCKS5 代理伺服器，搭配 `hev-socks5-tunnel`（tun2socks）將所有 VPN 流量導入代理。TCP 流量原先已正常，UDP（主要是 DNS）卻完全無法通過代理，導致網路不通。

---

## 問題彙整與修復記錄

### Bug 1：UDP Relay 沒有正確回報綁定 Port

**根本原因**

`SOCKS5UDPRelayServer` 原先使用 `NWUDPSocket` 作為監聽客戶端 UDP 封包的 Socket，但 `NWUDPSocket`（底層是 `NWUDPSession`）在 iOS 上設計為「連到指定遠端」，無法作為被動監聽的 Server Socket。結果 tun2socks 無法送封包進來。

**修復方式**

以 `GCDAsyncUdpSocket`（CocoaAsyncSocket）取代作為客戶端監聽 Socket，綁定 `127.0.0.1:0` 讓 OS 分配一個 ephemeral port，再透過 `socket.localPort()` 取得實際分配到的 port，回傳給 tun2socks 使用。

```swift
let socket = GCDAsyncUdpSocket(delegate: self, delegateQueue: ...)
try socket.bind(toPort: 0, interface: "127.0.0.1")
try socket.beginReceiving()
let boundPortValue = socket.localPort()
```

---

### Bug 2：UDP 封包送出後永遠等不到回應（VPN Routing Loop）

**根本原因**

這是最核心的 Bug。在 Network Extension 中，所有網路流量都會被 VPN TUN 介面攔截，再導入 tun2socks。如果向外送 UDP 封包時使用預設的 Socket（`NWUDPSocket`），iOS 系統會把封包**再次導回 VPN TUN 介面**，形成無限迴圈：

```
tun2socks → SOCKS5 Proxy → NWUDPSocket(8.8.8.8:53) →
  OS Route → TUN Interface → tun2socks → SOCKS5 Proxy → ...（無限循環）
```

封包永遠到達不了真正的 8.8.8.8，也因此永遠沒有回應。

**修復方式**

向外連接時必須使用 `NWCellularUDPSocket`，它底層透過 `NWConnection` 並強制指定 `.cellular` Interface（即 4G/5G 天線），讓封包直接走行動網路，**繞過 VPN TUN**。

問題的根源在於 `PacketTunnelProvider.swift` 建立 `GCDSOCKS5ProxyServer` 時，忘記設定 `outboundInterfaceType`，導致預設值 `.default` 一路傳遞下去，使 `RawSocketFactory` 錯誤地建立了 `NWUDPSocket` 而非 `NWCellularUDPSocket`。

**修復的程式碼**

```swift
// PacketTunnelProvider.swift
let server = GCDSOCKS5ProxyServer(address: ..., port: Port(port: 0))
server.outboundInterfaceType = .cellular  // ← 必須明確設定！
```

`RawSocketFactory.getRawUDPSocket` 現在會正確建立 `NWCellularUDPSocket`：

```swift
if activeInterface == .cellular {
    return NWCellularUDPSocket(host: host, port: port, timeout: timeout)
} else {
    return NWUDPSocket(host: host, port: port, timeout: timeout)
}
```

---

### Bug 3：UDP 回應 Header 的來源 IP 錯誤（SOCKS5 Protocol）

**根本原因**

當 `NWCellularUDPSocket` 收到外部 DNS 伺服器（如 8.8.8.8）的回應後，`SOCKS5UDPRelayServer` 需要把資料包裝成 SOCKS5 UDP 格式，再轉送回 tun2socks。原先程式碼把來源 IP 寫死為 `0.0.0.0:0`：

```swift
// 錯誤的實作
var response = Data([0x00, 0x00, 0x00, 0x01])  // SOCKS5 UDP header (IPv4)
response.append(contentsOf: [0, 0, 0, 0, 0, 0]) // ← 0.0.0.0:0 (假的！)
response.append(data)
```

tun2socks 或作業系統的 DNS Resolver 期待看到的來源 IP 是真正的 DNS Server（`8.8.8.8:53`），因為收到了來源是 `0.0.0.0` 的封包就直接丟棄。

**修復方式**

在 `NWCellularUDPSocket` 和 `NWUDPSocket` 上加入 `host` 和 `port` 公開屬性，記錄連線目標。`SOCKS5UDPRelayServer` 收到回應時，呼叫 `buildSOCKS5ResponseHeader(host:port:data:)` 正確構造 SOCKS5 UDP 回應 Header：

```swift
private func buildSOCKS5ResponseHeader(host: String, port: Int, data: Data) -> Data {
    var response = Data([0x00, 0x00, 0x00]) // RSV + FRAG
    
    if let ip = IPAddress(fromString: host), ip.family == .IPv4 {
        response.append(0x01) // ATYP = IPv4
        let ipBytes = host.components(separatedBy: ".").compactMap { UInt8($0) }
        response.append(contentsOf: ipBytes) // e.g. [8, 8, 8, 8]
    } else {
        response.append(0x03) // ATYP = Domain name
        let hostData = host.data(using: .utf8) ?? Data()
        response.append(UInt8(hostData.count))
        response.append(hostData)
    }
    
    response.append(UInt8((port >> 8) & 0xFF)) // Port high byte
    response.append(UInt8(port & 0xFF))         // Port low byte
    response.append(data)                        // DNS response payload
    
    return response
}
```

---

### Bug 4：UDP Socket 對象被 ARC 提前釋放

**根本原因**

`SOCKS5UDPRelayServer` 原先只用一個 `var outboundSocket: AnyObject?` 儲存向外連線的 Socket。每次收到新的 UDP 目標（如不同的 DNS Server）就會建立新 Socket，並覆蓋掉舊有的 reference，導致舊 Socket 被 ARC 釋放，正在等待中的連線因此中斷。

**修復方式**

改為使用字典，以 `"host:port"` 為 key 保存所有活躍的 outbound sockets：

```swift
private var outboundSockets: [String: AnyObject] = [:]
```

---

### Bug 5：多執行緒並行存取 Dictionary 導致崩潰

**根本原因**

在高流量 UDP 環境下（例如同時收到多個 DNS 回應），多條執行緒同時讀寫 `outboundSockets` 字典，引發記憶體損壞並拋出 `NSInvalidArgumentException: -[__NSTaggedDate count]: unrecognized selector`。

**修復方式**

在 `SOCKS5UDPRelayServer` 中加入 `serial DispatchQueue`，將所有字典讀寫與 `actualClientAddress` / `actualClientPort` 的修改都安排在同一條 queue 中序列執行：

```swift
private let queue = DispatchQueue(label: "com.zyxel.proxy.socks5udprelay")

private func getOrCreateOutboundSocket(host: String, port: Int) -> AnyObject? {
    let key = "\(host):\(port)"
    var existingResult: AnyObject?
    queue.sync { existingResult = outboundSockets[key] }
    if let existing = existingResult { return existing }
    
    // ... 建立新 socket ...
    
    queue.sync {
        if let existing = outboundSockets[key] {
            existingResult = existing
        } else {
            outboundSockets[key] = socketObj
            existingResult = socketObj
        }
    }
    return existingResult
}
```

---

### Bug 6：UDP Socket 遇到短暫錯誤即斷線

**根本原因**

`NWCellularUDPSocket` 和 `NWUDPSocket` 的 receive handler 在遇到任何錯誤時都會呼叫 `disconnect()`。由於 UDP 是無連線協定，網路上偶發的 POSIX 錯誤不代表連線失效，不應立即斷開。

**修復方式**

在 receive handler 中改為記錄錯誤但繼續 `readData()`，不立即中斷 socket：

```swift
// 修改前
if let error = error {
    disconnect()
    return
}

// 修改後
if let error = error {
    DDLogError("NWCellularUDPSocket receive error: \(error)")
    // 不中斷，繼續等待下一個 UDP 封包
}
if let data = data, !data.isEmpty {
    delegate?.didReceive(data: data, from: self)
}
self.readData() // 繼續接收
```

---

## 修改檔案清單

| 檔案 | 說明 |
|------|------|
| `tunnel/PacketTunnelProvider.swift` | 加入 `server.outboundInterfaceType = .cellular` |
| `src/ProxyServer/SOCKS5UDPRelayServer.swift` | 全面重寫：使用 `GCDAsyncUdpSocket`、正確的 SOCKS5 Header、字典式 Socket 管理、執行緒安全 |
| `src/RawSocket/NWCellularUDPSocket.swift` | 加入 `host`/`port` 屬性、修正 receive error 處理、加入 NWConnection 狀態日誌 |
| `src/RawSocket/NWUDPSocket.swift` | 加入 `host`/`port` 屬性、修正 receive error 處理 |

---

## 根本教訓

1. **在 VPN Network Extension 中，普通 Socket 會造成 Routing Loop**。所有往外的連線必須強制走 Cellular（或 Wi-Fi）介面，方法是使用 `NWConnection` 並設定 `NWParameters` 要求特定介面。
2. **SOCKS5 UDP ASSOCIATE 的回應 Header 必須嵌入真正的來源 IP/Port**，否則客戶端（包含 tun2socks、OS DNS Resolver）無法辨識來源並會丟棄封包。
3. **`AnyObject?` 作為 delegate 持有的 Socket reference 容易被 ARC 提早釋放**，應使用 `[String: AnyObject]` 明確保留所有活躍連線。
4. **高流量 UDP 環境下一定要確保 Dictionary 的執行緒安全**，使用 serial DispatchQueue 包裝所有共享狀態的讀寫。
