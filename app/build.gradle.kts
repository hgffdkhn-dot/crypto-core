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

        // 服务器长期公钥（服务端首次启动时打印）。
        // 生产环境应改为 gradle 变量/构建时注入，切勿长期放在 VCS 里的默认值。
        buildConfigField("String", "DEFAULT_HOST", "\"http://10.0.2.2:8080\"") // 模拟器访问宿主机
        buildConfigField("String", "SERVER_ED25519_PUB_B64", "\"REPLACE_WITH_YOUR_SERVER_KEY\"")
        buildConfigField("String", "SERVER_X25519_PUB_B64", "\"REPLACE_WITH_YOUR_SERVER_KEY\"")
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
        // Kotlin 1.9.24 ↔ Compose Compiler 1.5.15。升级 Kotlin 时务必同步查官方兼容表。
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

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":crypto-core"))
}
