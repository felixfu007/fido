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

    // 【診斷/harness 程式碼與正式產物分離】見任務回報「harness 分離」一節與
    // harness/FidoServerClient.kt、harness/HarnessActivity.kt、
    // keystore/DiagnosticsGate.kt（src/poc、src/prod 兩份）檔頭說明。
    //
    // `prod` flavor 完全不含 `harness/` 原始碼、不含 `activity_harness.xml`、manifest 不宣告
    // HarnessActivity——不是「打包了但沒入口」，而是這些檔案根本不在 prod 的編譯輸入內，
    // 物理上不可能出現在 prod 產出的 APK 裡。`HardwareKeyManager` 的軟體金鑰放行診斷開關
    // 同理：其實際狀態委派給 flavor 各自提供的 `keystore.DiagnosticsGate`
    // （src/prod 版本恆為 `false` 且無任何切換入口；src/poc 版本可由 HarnessActivity 切換）。
    //
    // 重新啟用診斷模式的方式：組建/安裝 `poc` flavor（如
    // `./gradlew :app:installPocDebug`），啟動後即為含 HarnessActivity 啟動器圖示的 APP，
    // 內建「診斷旗標」按鈕可切換 DiagnosticsGate。日常開發／CI 預設應建置 `prod` flavor
    // （如 `./gradlew :app:assembleProdDebug`）以確保產物不含診斷/測試專用程式碼。
    flavorDimensions += "distribution"
    productFlavors {
        create("prod") {
            dimension = "distribution"
        }
        create("poc") {
            dimension = "distribution"
            // 與 prod 並存安裝於同一裝置/模擬器，避免混淆哪個 APK 含 harness。
            applicationIdSuffix = ".poc"
            versionNameSuffix = "-poc-harness"
        }
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

    // 【poc flavor 專用】僅 harness/HarnessActivity.kt 使用 lifecycleScope + coroutines 做非同步
    // fido-server REST 呼叫；prod flavor 不含 harness 原始碼，改用 pocImplementation（而非
    // implementation）避免 prod 產物透過依賴圖帶入用不到的套件，作為「harness 真的被隔離」的
    // 額外佐證（不只是原始碼不編譯，連依賴都不會出現在 prod 的 APK/依賴樹）。
    // 用 add(configurationName, ...) 而非型別化的 pocImplementation(...) 存取器：Kotlin DSL
    // 的 flavor 專屬設定存取器要等一次成功 sync 後才會產生，同一個 build script 內直接用型別化
    // 寫法會出現 "Unresolved reference"（先有 productFlavors 才有存取器的雞生蛋問題）。
    add("pocImplementation", "androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    add("pocImplementation", "androidx.activity:activity-ktx:1.9.1")
    add("pocImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

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
