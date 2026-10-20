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
        versionCode = 11
        versionName = "0.10.0"
    }

    // 対象の Android のバージョンごとに APK を分ける（TetheringDedicated-<版>_android10.apk / _android11.apk）。
    // どちらも同じアプリ ID・同じ署名鍵なので、上書きで入れ替えられる（設定・ログは引き継ぐ）
    flavorDimensions += "os"
    productFlavors {
        // Android 10 用。Play ストアでは配布せず ADB・APK で導入するため targetSdk も 29 に固定し、
        // Android 11 以降で厳しくなった制限（設定値の読み取り・パッケージ一覧など）の影響を受けないようにする
        create("android10") {
            dimension = "os"
            minSdk = 29
            targetSdk = 29
        }
        // Android 11 用（v0.10.0 から）。Android 11 の仕様（targetSdk 30）に合わせ、Android 10 には入らない
        create("android11") {
            dimension = "os"
            minSdk = 30
            targetSdk = 30
        }
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
        // 画面の文言は文字列リソースではなく I18n.kt の tr()（日本語・英語・フランス語）で切り替えるので、SetTextI18n も対象外
        // Android 10 用と Android 11 用（minSdk 30）で同じコードを使うので、Android 11 用の版では不要に見える SDK_INT の判定がある（ObsoleteSdkInt）
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi", "BatteryLife", "SetTextI18n", "ObsoleteSdkInt")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
