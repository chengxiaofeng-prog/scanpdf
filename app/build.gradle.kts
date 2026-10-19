plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.scanpdf.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scanpdf.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Compose + material-icons-extended 方法数已超 64K，minSdk 24 走原生 multidex；
        // 显式打开，避免个别 AGP 版本在 debug 变体上忘记开启导致运行期 NoClassDefFoundError。
        multiDexEnabled = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    buildFeatures {
        compose = true
    }

    lint {
        // 首次构建优先保证能出包；lint 问题单独看报告，不阻断 CI
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // LocalLifecycleOwner / lifecycleScope 需要
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Dispatchers.Main 显式依赖：不能指望它由传递依赖间接带进来
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 离线 OCR（中文 + 拉丁字母 + 数字），模型随 APK 打包，无需联网
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
