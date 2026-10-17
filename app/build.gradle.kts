plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.enchat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enchat.app"
        minSdk = 26          // Android 8.0：Keystore StrongBox 与 Room 均有较好支持
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"

        // ⚠️ 服务器长期公钥（首次启动时由服务端生成并打印）。
        //   必须与目标服务器上 /var/lib/enchat/keys/server-identity.key 对应，
        //   否则客户端握手时 pin 校验失败，表现为一直显示「连接中…」。
        //   换服务器 / 密钥文件丢失重建后，必须同步更新这里并重新发布客户端。
        buildConfigField("String", "SERVER_ED25519_PUB_B64", "\"sci64uPNph+ql/Xi+upQdQRF1DuP3PflczCZVrI9G6Q=\"")
        buildConfigField("String", "SERVER_X25519_PUB_B64", "\"id+vFwFJbctVUeBhZsSmSf22HjhmOCSATGrDO567s10=\"")

        // 默认服务器地址（已配好 TLS：server.servebeer.com）
        // 注意：清单里 usesCleartextTraffic=false，明文 http 会被系统拒绝，
        // 所以这里必须是 https。
        buildConfigField("String", "DEFAULT_HOST", "\"https://server.servebeer.com\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // ⚠️ Compose Compiler 必须与 Kotlin 版本严格对应：
        // Kotlin 1.9.25 ↔ Compose Compiler 1.5.15（见 gradle/libs.versions.toml）。
        // 升级 Kotlin 时务必同步查官方兼容表：
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":crypto-core"))
    implementation(project(":client-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ⚠️ Android 单元测试源集默认只支持 JUnit4；JUnit5 需额外引入 android-junit5 插件。
    // 本模块暂无测试代码，先按 JUnit4 预留。
    testImplementation(libs.junit4)
    testImplementation(project(":crypto-core"))
}
