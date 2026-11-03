plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.notifyledger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.notifyledger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.all {
    resolutionStrategy {
        // 让 base/common 对齐, 避免 16 与 19 类重复
        force("com.google.android.gms:play-services-mlkit-text-recognition-common:17.1.0")
    }
}

dependencies {
    // 批1 依赖: 逐条升级, 每条独立提交(目标: appcompat 1.8.0 / material 1.14.0 / recyclerview 1.4.0 / core-ktx 1.19.1)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // ML Kit 本地 OCR(离线): chinese 语言包(v2 API, 含 TextRecognizer 接口与 Options)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
