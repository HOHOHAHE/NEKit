# iOS 與 Android 在 Wi-Fi 網路下強制使用 Cellular (行動網路) 的行為差異與底層原理

在開發 VPN 或是連線代理 (Proxy) 相關應用程式時，開發者經常會遇到一個需求：**在設備連接著 Wi-Fi 的情況下，強制將特定流量引導至 Cellular（行動網路）**。

雖然在 iOS 網路層可以透過設定 Socket（例如 `NWParameters` 或 `NWConnection` 的 `requiredInterfaceType = .cellular`，或是本文提到的 `outboundInterfaceType = .cellular`）來指定介面，但開發者往往會發現， iOS 在這種情況下的行動網路速度（例如只有 50 Mbps）遠低於關閉 Wi-Fi 時的純行動網路速度（例如 400~500 Mbps）。

反觀在 Android 上，這種現象卻較少發生，或者有明確對應的 API 可以避開。這主要是由於兩大作業系統在**網路架構設計**、**硬體電源管理 (Power Management)** 以及**權限開放程度**上的根本差異。

---

## 核心差異比較表

| 比較項目 | iOS (Apple) | Android (Google & OEM) |
| --- | --- | --- |
| **網路調度哲學** | 極致的省電與控制，以系統決定的 Primary Interface 為主。 | 較高的彈性，開發者可主動改變硬體狀態。 |
| **硬體喚醒 API** | **無**。一般 App（含 Network Extension）無法越權控制基帶晶片 (Baseband)。 | **有**。透過 `ConnectivityManager.requestNetwork()` 主動喚醒。 |
| **多網路併發效能** | 次要網路（Cellular）會被強制降為「低功耗模式」（關閉載波聚合 CA，降速）。 | 只要申請成功，次要網路仍可保持「全功率活躍狀態」，實現雙網滿速。 |
| **系統級別魔改** | 僅允許 Apple 自家服務（如 Siri、Apple Music）享有雙網併發特權。 | 許多手機廠（三星、小米等）原生支援並極度優化「雙網加速/併發」功能。 |
| **VPN 綁定彈性** | 透過 `NEPacketTunnelNetworkSettings` 配置為主，網路切換由系統主導。 | 透過 `VpnService.setUnderlyingNetworks()` 可明確綁定並喚醒底層網路。 |

---

## 深入探討

### 1. iOS 的被動式軟體路由與電源限制

在 iOS 中，這套機制的運作模式如下：

*   **當只有 Cellular (沒有 Wi-Fi) 時**：
    系統判斷行動網路是設備唯一的對外連線。為提供最佳的網路體驗，iOS 會將基帶晶片 (Baseband) 調整為**全速模式 (High-power state)**：
    *   啟用所有的**載波聚合 (Carrier Aggregation, CA)**。
    *   啟用完整的多天線收發 (MIMO)。
    *   極限速度可達 400~500 Mbps。
*   **當連上 Wi-Fi 時**：
    Wi-Fi 立刻成為 `Primary Interface`。為了**大幅節省電池電量**，iOS 會主動將行動網路晶片降級到**「低功耗 / 待機模式」(Low-power state)**：
    *   關閉載波聚合 (CA)。
    *   減少啟用的天線數量。
    *   進入省電的 RRC (Radio Resource Control) 狀態，限制網路發射功率與頻寬。

**結論**：即便你在程式碼中強制設定了 `server.outboundInterfaceType = .cellular`，這僅是在「軟體層面」告訴 iOS 把封包從 Cellular (pdp_ip0) 丟出去。App **無法改變硬體層面的省電策略**。因此，封包確實走行動網路出去了，但走的是一條**被系統強制縮減頻寬的省電行動網路**，導致速度受限（約 50 Mbps 左右）。如果要解除這個硬體限速，只能讓設備主動斷開 Wi-Fi。

---

### 2. Android 的主動請求機制與雙網優化

Android 提供了更底層、更具掌控權的 API，讓開發者可以直接影響硬體狀態：

*   **主動喚醒能力 (`ConnectivityManager`)**：
    Android 開發者可以透過 `ConnectivityManager` 的 `requestNetwork()` API **「主動地」向系統申請喚醒行動網路**。
    ```java
    NetworkRequest request = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build();
    
    ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    connectivityManager.requestNetwork(request, networkCallback);
    ```
    當執行上述請求時，Android 系統會真的喚醒基帶晶片，讓它進入活躍的高功率狀態 (Active State)。接著再透過 `network.bindSocket()` 綁定流量，此時跑的就是全速的行動網路，載波聚合 (CA) 也能正常運作。
*   **各家廠商的「雙網併發」優化**：
    許多 Android OEM 廠商（如三星「下載加速器」、小米 / OPPO「雙網加速」）本身就將系統底層修改為**允許 Wi-Fi 和 4G/5G 同時處於全速運作狀態**。這使得 Android 底層硬體原本就習慣於兩個網路介面同時滿載運作的場景。
*   **VPN 的底層網路控制 (`VpnService`)**：
    Android 的 `VpnService` 允許在建立 VPN tunnel 時，呼叫 `setUnderlyingNetworks()` 來明確指定依賴的底層網路。這進一步告知系統這個 VPN 必須維持 Cellular 模組的活躍度。

**結論**：開發者在 Android 上透過正規 API 申請後，系統會實際調度硬體資源（基帶與天線）給你用。只要申請成功，就能利用基帶晶片硬體應有的最大效能。因此，這類「Wi-Fi 連線下強制使用全速 Cellular」的應用場景中，Android 的自由度與表現遠超 iOS。
