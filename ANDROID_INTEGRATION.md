# NEKit Kotlin Android 整合指南

這個文件說明如何在你的 Android 專案中整合 NEKit Kotlin 函式庫。

## 方法一：本地 AAR 檔案整合

### 1. 建置 AAR 檔案

在 NEKit 專案根目錄執行：

```bash
./gradlew assembleRelease
```

這會在 `build/outputs/aar/` 目錄下生成 `nekit-release.aar` 檔案。

### 2. 在你的 Android 專案中整合

#### 步驟 1：複製 AAR 檔案
將 `nekit-release.aar` 複製到你的 Android 專案的 `app/libs/` 目錄下。

#### 步驟 2：修改 app/build.gradle
```kotlin
android {
    // ... 其他配置
}

dependencies {
    implementation files('libs/nekit-release.aar')
    
    // NEKit 的依賴項（必須手動添加）
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
    implementation 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2'
    implementation 'com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2'
    implementation 'com.maxmind.geoip2:geoip2:4.0.1'
    implementation 'org.slf4j:slf4j-api:2.0.7'
    implementation 'org.slf4j:slf4j-android:1.7.36'
    implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'io.ktor:ktor-network:2.3.7'
    implementation 'net.java.dev.jna:jna:5.13.0'
}
```

## 方法二：作為 Git Submodule 整合

### 1. 添加為 Submodule
在你的 Android 專案根目錄執行：

```bash
git submodule add https://github.com/your-username/NEKit.git nekit
```

### 2. 修改 settings.gradle
```kotlin
include ':app'
include ':nekit'
project(':nekit').projectDir = new File('nekit')
```

### 3. 修改 app/build.gradle
```kotlin
dependencies {
    implementation project(':nekit')
    // 其他依賴項會自動包含
}
```

## 方法三：發布到 Maven Repository

### 1. 發布到本地 Maven Repository
在 NEKit 專案根目錄執行：

```bash
./gradlew publishToMavenLocal
```

### 2. 在你的專案中引用
修改你的 Android 專案的 `build.gradle`：

```kotlin
repositories {
    mavenLocal()
    // 其他 repositories
}

dependencies {
    implementation 'com.github.nekit:nekit-kotlin:1.0.0'
}
```

## 使用範例

### 基本使用
```kotlin
import com.github.nekit.Config.Configuration
import com.github.nekit.Rule.RuleManager
import com.github.nekit.Socket.ProxySocket.ProxySocket

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 NEKit 配置
        try {
            val configFile = File(filesDir, "config.yaml")
            Configuration.parseConfigurationFile(configFile.absolutePath)
            
            // 使用 NEKit 功能
            // ...
            
        } catch (e: Exception) {
            Log.e("NEKit", "Failed to initialize NEKit", e)
        }
    }
}
```

### 權限設定
在你的 `AndroidManifest.xml` 中添加必要的權限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## 注意事項

1. **最低 SDK 版本**：此函式庫要求 Android API 21 (Android 5.0) 或更高版本。

2. **ProGuard 配置**：如果你的專案啟用了代碼混淆，consumer-rules.pro 會自動應用必要的 ProGuard 規則。

3. **網路安全配置**：如果你的應用需要使用自定義的網路安全配置，請確保允許必要的網路連接。

4. **依賴衝突**：如果遇到依賴版本衝突，請根據你的專案需求調整版本號。

## 疑難排解

### 常見問題

**Q: 編譯時出現 "Duplicate class" 錯誤**
A: 檢查是否有重複的依賴項，使用 `./gradlew app:dependencies` 查看依賴樹。

**Q: 運行時出現 ClassNotFoundException**
A: 確保所有必要的依賴項都已正確添加到你的專案中。

**Q: 網路連接失敗**
A: 檢查網路權限和網路安全配置是否正確設置。

## 支援

如果遇到問題，請在 GitHub 上提交 issue 或查看原始 NEKit 專案的文檔。