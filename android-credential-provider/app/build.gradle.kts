plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fido.credentialprovider"
    // compileSdk=35：androidx.credentials 1.5.0 的 AAR metadata 要求編譯期至少對到 API 35
    // stub（見 build.gradle.kts 註解）。minSdk/targetSdk 仍固定 34，對齊 CLAUDE.md
    // 「僅 Android 14+；不支援舊版 Android」——執行期最低支援版本不受 compileSdk 影響。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fido.credentialprovider"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // PoC 階段不簽 release；本專案僅產出 debug APK 供側載測試（見任務要求第 8 點）。
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CredentialProviderService / Credential Manager provider-side API（androidx.credentials.provider.*）。
    implementation("androidx.credentials:credentials:1.5.0")

    // 系統裝置解鎖/生物辨識確認（UV=required 的使用者驗證閘門，見清單項目 9）。
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 測試 ----
    testImplementation("junit:junit:4.13.2")
    // 僅 JVM 單元測試使用：驗證本專案手寫的 CBOR 編碼器（webauthn/Cbor.kt）產出的 bytes，
    // 能被 fido-server 實際使用的同一套 CBOR 函式庫（jackson-dataformat-cbor）正確解回，
    // 確保與伺服器端 AttestationObjectParser 的位元組層級相容 —— 這是清單項目 3
    // 「結構自洽」判準的關鍵驗證手段之一。test-only：不會被打進 APK，不影響正式執行路徑依賴。
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // 僅 JVM 單元測試使用：組出「密碼學上合法」的 Android Key Attestation 測試憑證鏈
    // （比照 fido-server 的 TestKeyAttestationFixtures 做法），驗證本專案手寫的 CBOR 編碼器
    // 產出的 attestationObject 位元組能被完整解析且欄位自洽。test-only，不影響 APK。
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
