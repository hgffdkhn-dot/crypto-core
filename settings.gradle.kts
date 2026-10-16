rootProject.name = "EnChat"

include(":crypto-core")
include(":client-core")
include(":server")
include(":app")

// :app 需要 Android SDK 与 Google Maven。若本地无 SDK，可先只构建其余模块：
//   gradle :crypto-core:build :client-core:build :server:test
