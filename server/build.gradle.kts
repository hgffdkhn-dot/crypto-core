plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.enchat"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.enchat.server.ApplicationKt")
}

dependencies {
    // 核心加密与协议（与 Android 客户端同一份实现，避免两端行为漂移）
    implementation(project(":crypto-core"))

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.statuspages)
    implementation(libs.ktor.server.core)

    implementation(libs.json)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.h2)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    maxHeapSize = "1g"
    // 集成测试会真实启动 Netty + H2，并做 Argon2 派生，超时给足
    systemProperty("junit.jupiter.execution.timeout.default", "300 s")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
