plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.enchat"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":crypto-core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
