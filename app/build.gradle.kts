plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsh.aianswer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.aianswer"
        minSdk = 26
        targetSdk = 34
        // 版本号交给 -Pvc / -Pvn，build.sh 里改一处即可
        versionCode = (project.findProperty("vc") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("vn") as String?) ?: "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 签名可选：keystore 不入库（.gitignore），有就签名，没有就出未签名 APK
    val ksFile = rootProject.file("keystore/aianswer.jks")

    signingConfigs {
        if (ksFile.exists()) {
            create("rel") {
                storeFile = ksFile
                storePassword = System.getenv("KS_PASS") ?: "android"
                keyAlias = "qh"
                keyPassword = System.getenv("KS_PASS") ?: "android"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("rel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 本地中文 OCR（ML Kit 文字识别 v2 · bundled）
    // 模型直接打进 APK，不联网、不依赖 Google 服务、不产生调用费用。
    // 代价是 APK 变大约 20MB，识别在本机完成（1280×2772 截图约 0.3~0.8 秒）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}

// 产物统一落到 构建/ 目录，文件名带版本号
tasks.whenTaskAdded {
    if (name == "assembleRelease") {
        doLast {
            val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
            if (apk.exists()) {
                val out = rootProject.file("构建")
                out.mkdirs()
                val v = android.defaultConfig.versionName
                apk.copyTo(File(out, "AI答题-v$v.apk"), overwrite = true)
                println("APK -> 构建/AI答题-v$v.apk")
            }
        }
    }
}
