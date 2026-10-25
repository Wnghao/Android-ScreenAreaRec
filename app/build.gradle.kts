plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androad.arearec"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.androad.arearec"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 刻意不引任何第三方 / AndroidX 依赖。
    // MediaProjection + EGL + MediaCodec + MediaMuxer 全是系统 API，零依赖构建最快。
}
