// 根项目：统一声明插件版本，子模块用 alias(...) 引用且不再写版本。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
}

tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
}
