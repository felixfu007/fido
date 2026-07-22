// FIDO Credential Provider PoC — 根 Gradle 檔。
//
// 對齊 CLAUDE.md「僅 Android 14+」：整個專案 minSdk=targetSdk=compileSdk=34（見 app/build.gradle.kts）。
plugins {
    // AGP 8.7.x 需要以支援 compileSdk 35（androidx.credentials 1.5.0 的 AAR metadata 要求
    // compileSdk>=35 才能編譯；minSdk/targetSdk 仍固定 34，對齊 CLAUDE.md「僅 Android 14+」，
    // compileSdk 只影響編譯期可用的 API stub，不影響執行期最低支援版本）。
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
