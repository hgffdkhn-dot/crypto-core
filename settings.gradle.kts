pluginManagement {
    repositories {
        // ⚠️ 关键：Android Gradle Plugin 只发布在 Google Maven，不在 Gradle Plugin Portal。
        // 少了这三行就会报 "Plugin [id: 'com.android.application'] was not found"。
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 统一在本文件声明仓库，禁止各模块再写 repositories{}，避免仓库来源不一致。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // AndroidX / Compose / Room / AGP 相关
        mavenCentral()  // BouncyCastle / Ktor / OkHttp / JUnit
    }
}

rootProject.name = "EnChat"

include(":crypto-core")
include(":client-core")
include(":server")
include(":app")

// :app 需要 Android SDK；若本地无 SDK，可先只构建其余模块：
//   gradle :crypto-core:build :client-core:build :server:test
