rootProject.name = "nekit-kotlin"

// 包含主要的 Kotlin 模組
include(":nekit")

// 設定模組的專案目錄
project(":nekit").projectDir = file(".")