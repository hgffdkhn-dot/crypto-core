plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.enchat"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 只用 BouncyCastle 的 lightweight API：Android 与 JVM 服务端行为一致，
    // 且不依赖 JCE Provider 注册（避免与 Android 自带裁剪版 BC 冲突）。
    api(libs.bcprov)

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // 加密测试含 Argon2（内存硬 KDF），给足内存与超时
    maxHeapSize = "1g"
    systemProperty("junit.jupiter.execution.timeout.default", "120 s")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
