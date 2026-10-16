// 根项目：当前只做统一配置，不引入 Android/服务端插件（模块到位后再补）
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
}
