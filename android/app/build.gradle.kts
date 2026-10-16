import java.util.Properties

plugins {
    id("com.android.application")
}

// 署名設定（keystore.properties が無ければ debug 署名でビルド）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "local.tethering.dedicated"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.tethering.dedicated"
        // 対象は Android 10。Play ストアでは配布せず ADB で導入するため targetSdk も 29 に固定し、
        // Android 11 以降で厳しくなった制限（設定値の読み取り・パッケージ一覧など）の影響を受けないようにする
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // ADB 導入専用（Play 配布しない）ため、Play のポリシー由来の指摘は対象外にする:
        // targetSdk 29 は意図的（上記）、電池の最適化からの除外依頼は監視アプリとして必要
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi", "BatteryLife")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
