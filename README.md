# FIDO/FIDO2 多租戶認證平台

一套可授權給電商廠商整合的 FIDO2/WebAuthn 無密碼登入解決方案。平台以「加掛」形式強化既有帳密系統，
不取代帳密（帳密永久保留作為救援手段），透過手機硬體安全區（TEE/StrongBox）產生的裝置金鑰完成生物辨識
登入，並將裝置管理、憑證撤銷、跨行程 session 交接等能力整套提供給採用方。

## 這是什麼

四個系統協同運作，構成一套完整、可交付的 FIDO2 認證解決方案：

| 系統 | 說明 |
|---|---|
| **`fido-server/`** | 多租戶 FIDO2 驗證伺服器（Spring Boot）。RP（Relying Party）端邏輯的核心：WebAuthn 挑戰簽發、真實密碼學簽章驗證、Android Key Attestation 憑證鏈驗證與 TEE/StrongBox 硬體等級判讀、租戶隔離、裝置生命週期管理、短時效 session JWT 簽發。**僅作驗證服務，不是身分來源**——採用方既有帳密系統才是身分權威。 |
| **`android-credential-provider/`** | 自訂 Android `CredentialProviderService`，掛載於系統 Credential Manager。負責在使用者手機上以硬體安全金鑰完成 WebAuthn 註冊/登入 ceremony，產出伺服器可驗證的 `android-key` attestation。**不是獨立跳轉的 App**——所有認證流程均由系統以 PendingIntent 隱式驅動，App 本身僅有一個最小化的「設定/狀態」啟動器畫面。 |
| **`shopping-site-reference/`** | 「採用 FIDO 登入的購物網站」串接參考範例（Spring Boot）。示範採用方後端應如何以 server-to-server 方式呼叫 `fido-server` REST API 完成註冊/登入代理、獨立驗證 session JWT（不信任回應的 `verified` 欄位）、裝置管理代理。供實際簽約的購物網站工程團隊照抄整合模式，非正式產品的一部分。 |
| **資料主機（SQL Server）** | 獨立於其他系統的資料庫實例，七張核心表，TDE 全庫加密 + 定期備份。建置腳本見 `infra/sql/`。 |

## 核心特色

- **硬體綁定金鑰**：強制要求 TEE/StrongBox 安全區，驗證完整 Android Key Attestation 憑證鏈，不通過即拒絕註冊——私鑰從不離開手機安全硬體。
- **多租戶隔離**：`X-API-Key` 決定租戶，每租戶獨立速率限制（100 TPS）、獨立 origin/App 綁定允許清單。
- **兩種前端存取情境**：手機瀏覽器存取購物網站、購物網站原生 Android App 內直呼 Credential Manager（後者採每租戶 opt-in，需完成 Digital Asset Links 綁定）。
- **多裝置管理與撤銷**：一人可註冊多台裝置並獨立管理；撤銷採雙層防禦（`allowCredentials` 排除 + 驗證前狀態檢查），已在實體裝置上驗證撤銷後的裝置確實無法再登入且不影響其他裝置。
- **防帳號列舉**：登入與裝置管理 API 一律 200 + 空清單/冪等 no-op，不以 404 洩漏使用者或裝置是否存在。
- **短時效 session JWT 交接**：ES256 簽章、120 秒有效期、JWKS 端點公鑰輪替，購物網站端獨立驗證簽章與 audience，不依賴 fido-server 回應內容。

## 技術棧

- **後端**：Java 21、Spring Boot 3、Maven；`fido-server`/`shopping-site-reference` 皆為獨立部署單元，`shopping-site-reference` 刻意不依賴 `fido-server` 原始碼或 Maven artifact（真實採用方也不會拿到）。
- **Android**：Kotlin、Gradle，Android 14+（API 34）、`androidx.credentials`，Gradle product flavor 分離正式產物（`prod`）與 PoC 診斷 harness（`poc`）。
- **資料庫**：SQL Server（正式環境）／H2（本機開發，`fido.persistence.mode=memory` 亦可完全略過資料庫跑本機測試）。
- **CI**：GitHub Actions（`.github/workflows/ci.yml`）——兩個 Maven 專案的單元測試、Android 兩個 flavor 的 JVM 單元測試，以及一個會真的啟動兩個獨立 JVM、跑完整跨行程 HTTP 流程的整合測試 job。

## 目前狀態（v1.0.0）

核心功能（WebAuthn 註冊/登入/撤銷、多裝置管理、origin 綁定、租戶隔離、session JWT 交接、IDOR 防護）
皆已通過自動化測試與**實體 Android 裝置（Pixel 9）端對端驗證**，包含真實 StrongBox 等級硬體金鑰與
Chrome 瀏覽器完整 ceremony。已知限制與尚未涵蓋的情境（例如桌機瀏覽器目前不支援、OEM 多樣性未全面
覆蓋）詳見技術限制手冊（見下方「文件索引」）。完整決策脈絡與逐項驗證紀錄見 [`CLAUDE.md`](CLAUDE.md)。

## 文件索引

**架構與規格**
- [`CLAUDE.md`](CLAUDE.md) — 專案共識：全部架構決策、開發歷程、驗證紀錄的權威來源
- [`docs/api-contract.md`](docs/api-contract.md) — REST API 合約
- [`docs/db-schema.md`](docs/db-schema.md) — 資料庫 schema（七張核心表）
- [`docs/origin-binding.md`](docs/origin-binding.md) — WebAuthn origin 綁定與原生 App 存取情境設計
- [`docs/android-poc-checklist.md`](docs/android-poc-checklist.md) — Android Credential Provider 技術驗證 PoC 逐項紀錄

**給採用廠商的建置與維運文件**
- [`docs/vendor/environment-setup-guide.md`](docs/vendor/environment-setup-guide.md) — 環境建置手冊
- [`docs/vendor/api-integration-guide.md`](docs/vendor/api-integration-guide.md) — API 規格書與串接手冊
- [`docs/vendor/usage-scenarios-guide.md`](docs/vendor/usage-scenarios-guide.md) — 使用情境手冊
- [`docs/vendor/maintenance-guide.md`](docs/vendor/maintenance-guide.md) — 維護手冊
- [`docs/vendor/technical-limitations.md`](docs/vendor/technical-limitations.md) — 技術限制手冊

## 授權

見 [`LICENSE`](LICENSE)。
