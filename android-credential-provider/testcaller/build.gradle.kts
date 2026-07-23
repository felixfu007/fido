// 【驗證專用、非產品程式碼】對應「原生 App 情境 origin 解析」的實機/模擬器端對端驗證缺口
// （見任務回報）。這個獨立模組跟 :app 完全沒有依賴關係、也不會出現在 :app 的任何建置產物或
// 依賴圖中——它是一個「假裝自己是購物網站原生 App」的獨立 APK，簽章憑證與 :app 不同、
// applicationId 也不同（com.fido.testcaller vs com.fido.credentialprovider），刻意如此才能
// 真正驗證 OriginResolver 的「原生 App 路徑」：provider 從 CallingAppInfo.signingInfo 取得
// 呼叫方（本模組）的真實簽章憑證，換算成 `android:apk-key-hash:...`，而不是任何寫死值。
//
// 用法：
//   1. 建置並安裝 :app 的 prod flavor debug（真正的 provider）與本模組（呼叫方）到同一台
//      模擬器/裝置。
//   2. 啟動本模組的 MainActivity，會呼叫 CredentialManager.createCredential()（無指定
//      origin，走原生 App 路徑），觸發系統 Credential Manager bottom sheet。
//   3. 人工／adb 選擇本專案的 provider entry，觀察 provider 端 logcat 印出的
//      「origin 解析完成：sourceType=NATIVE_APP origin=android:apk-key-hash:...」。
//   4. 用 apksigner/keytool 獨立算出本模組 debug 簽章憑證的 SHA-256，比對 provider 記錄的值
//      是否一致（見任務回報的驗證紀錄，非本檔案職責）。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fido.testcaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fido.testcaller"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-originverify"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
