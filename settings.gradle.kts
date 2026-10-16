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

// :app 需要 Android SDK：配置阶段 AGP 就要定位 SDK，找不到会直接失败，
// 进而连 :crypto-core / :server 这些纯 JVM 模块也一起构建不了。
// 因此只在能定位到 SDK 时才纳入，纯 JVM 环境下自动跳过。
val androidSdkDetected =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootDir.resolve("local.properties").exists()

if (androidSdkDetected) {
    include(":app")
} else {
    logger.lifecycle("跳过 :app（未检测到 Android SDK）。设置 ANDROID_HOME 或创建 local.properties 后可构建 Android 模块。")
}
