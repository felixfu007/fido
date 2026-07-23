# FIDO/FIDO2 認證系統 — 專案共識

四個系統：FIDO 驗證伺服器、FIDO 驗證 APP（Android）、採用 FIDO 登入的購物網站、資料主機（SQL Server）。
後端：Spring Boot + Maven。架構情境：**A（標準 WebAuthn，同裝置）**，情境 B（跨裝置推播）已放棄。情境 A 的使用者存取涵蓋「手機瀏覽器存取購物網站」與「購物網站原生 Android App 內直呼 Credential Manager」兩種前端情境（後者對每個租戶 opt-in，見「WebAuthn origin 綁定 / 存取情境」決策與 `docs/origin-binding.md`）。

## 已確認的關鍵架構決策

| 主題 | 決策 |
|---|---|
| FIDO 伺服器定位 | 多租戶平台，僅作後端驗證服務（非身分來源） |
| RP ID | 購物網站網域 |
| 身分權威來源 | 購物網站既有帳密系統；FIDO 為加掛選項，帳密永久保留（不可單獨用 FIDO 登入取代帳密救援） |
| FIDO 驗證 APP 角色 | 自訂 Android `CredentialProviderService`，掛載於系統 Credential Manager（非獨立 APP 跳轉、非推播） |
| FIDO 驗證 APP 啟動器畫面 | `prod` flavor 保留**一個**最小化「設定/狀態」啟動器 Activity（Option B）。範圍嚴格限定：顯示 provider 是否已在系統啟用、一顆深連結進入系統 credential-provider 設定的按鈕、版本/客服/隱私（個資法）文字。**嚴禁**在此畫面內做任何登入/註冊 ceremony 或裝置列表/撤銷 UI——那會使其退化為 `非獨立 APP 跳轉` 決策所禁止的並行認證/管理路徑。ceremony 一律仍走 Credential Manager（由系統以 PendingIntent 隱式啟動 `CreatePasskeyActivity`/`GetPasskeyActivity`）。詳見下方「啟動器畫面決策」 |
| 支援裝置 | 僅 Android 14+；不支援舊版 Android，第一版不支援 iOS |
| 金鑰保護 | 強制要求 TEE/StrongBox 硬體安全區，不通過則拒絕註冊；驗證 Android Key Attestation 憑證鏈 |
| 多裝置 | 允許使用者註冊多台裝置，提供管理介面 |
| 帳號救援 | 既有帳密為主，客服人工為後盾 |
| 憑證撤銷 | 使用者主動撤銷 + 簽章異常（sign counter 倒退）自動撤銷 |
| 裝置撤銷方式 | 軟刪除（`status=REVOKED`），不實體刪列，保留 1 年稽核 |
| API 風格 | 同步 REST API + 短時效自簽 JWT 做購物網站與 FIDO 伺服器的 session 交接 |
| API 版本策略 | URI 路徑版本 `/api/v1` |
| API 認證 Header | `X-API-Key`（必，決定租戶）+ 選用 `X-Tenant-Id`（交叉檢查）/ `X-Request-Id`（追蹤） |
| 租戶速率限制 | 每租戶 100 TPS，超過回 `429` + `Retry-After` |
| Session JWT 演算法 | ES256（EC P-256），公鑰經 JWKS 端點提供 |
| Session JWT 有效期 | 120 秒 |
| 防帳號列舉策略 | 登入與裝置管理 API 一律採 200 + 空清單 / 冪等 no-op，不用 404 洩漏使用者或裝置是否存在 |
| 傳輸安全 | TLS + API Key |
| WebAuthn origin 綁定 / 存取情境 | v1 provider 一併支援「瀏覽器存取」與「購物網站原生 App 直呼 Credential Manager」兩情境；原生 App 情境對每個租戶採 **opt-in**（租戶須完成 `assetlinks.json` Digital Asset Links onboarding + 平台登錄 App 簽章指紋至 `tenant_app_bindings`）。origin 由 provider 從呼叫方動態、經驗證取得（不寫死），伺服器以 `expected_origin`(web) ∪ `tenant_app_bindings`(app) 允許清單把關，不符回 `403 ORIGIN_NOT_ALLOWED`。詳見 `docs/origin-binding.md` |
| Challenge 時效 | 60 秒，逾時前端自動重新申請 |
| 資料庫 | 獨立 SQL Server 實例（與其他系統分開）；七張核心表：`tenants`, `fido_user_ref`, `fido_credentials`, `bound_devices`, `auth_challenges`, `audit_log`, `tenant_app_bindings`（第七張為原生 App 情境的 Digital Asset Links 授權登錄，見 `docs/origin-binding.md`） |
| 加密/備份 | TDE 全庫加密 + 標準定期備份 |
| 容量目標 | 中小規模：數萬會員，峰值 ≤100 TPS |
| 部署 | 全地端部署（非雲端） |
| 法規範圍 | 適用台灣個資法，不涉金流相關法規 |
| 稽核紀錄保留 | 1 年 |
| FIDO Conformance Testing | 不在第一版投入，上線後再評估 |

## 目前階段

可行性評估與 22 項規格缺口已全數確認。REST API 合約（`docs/api-contract.md`）與核心表 DB schema（`docs/db-schema.md`，原六張、因 origin 綁定定案新增 `tenant_app_bindings` 後為七張）已定案；`fido-server` 後端骨架已建立，含真實 WebAuthn 密碼學驗證（簽章驗證、Android Key Attestation 憑證鏈驗證、TEE/StrongBox 判讀）、完整測試涵蓋率、JPA/Hibernate 持久層（本機開發用 H2，正式部署接 SQL Server，`infra/sql/` 已備妥建置腳本待正式環境套用）。

Android Credential Provider 技術驗證 PoC 的 10 項驗證項目已具體定案，見 `docs/android-poc-checklist.md`（含每項的關鍵/非關鍵標記、模擬器可驗證/須實機驗證標註、通過判準）。與原本「#1–5 為關鍵項目」的粗略說法相比，定案後的關鍵項目改為聚焦「地基掛載 + 硬體金鑰 + android-key 格式對接」（清單項目 1–5），**登入 ceremony 端對端重新歸類為非關鍵**（assertion 簽章與硬體無關、伺服器端已有測試覆蓋）。

開發環境目前沒有實體 Android 裝置，僅能用模擬器（Android 14 / API 34）驗證；PoC「通過」採**條件式通過（pending 實機）**：模擬器可驗證項目全數通過、關鍵風險已排除，即可先行推進正式開發時程，硬體聲明真偽與 OEM 相容性列為取得實機後的收尾驗證項目，不阻塞整體進度。

**PoC 執行完畢，結論：條件式通過（pending 實機）。** `android-credential-provider/` 專案已建立（`FidoCredentialProviderService` + StrongBox/TEE 金鑰產生 + 手寫 CBOR 組出 `fmt=android-key` attestationObject），關鍵項目 1–5 在模擬器上全數驗證通過，其中風險最高的項目 3（自訂 provider 能否產出伺服器可解析的 android-key 格式）已用**模擬器真實 Android Keystore 吐出的憑證鏈**（非僅 JVM 自簽測試 fixture）端對端送驗 `fido-server`（`fido.attestation.mode=real`）確認可行。硬體安全等級本身（StrongBox/TEE 是否真的達標）因模擬器無真實安全硬體，仍待實體裝置驗證；`fido-server` 新增 `fido.attestation.poc-trust.*` 設定（預設關閉、僅供 PoC 期間額外信任模擬器測試 root，不影響正式路徑的 Google root 信任集合）。詳細逐項結果見執行紀錄。原本待釐清的「原生 App 情境下的 origin 綁定架構」已由 systems-analyst 定案於 `docs/origin-binding.md`（OB1–OB6 六項決策經人類拍板，含新增第七張表 `tenant_app_bindings`、`ORIGIN_NOT_ALLOWED` 錯誤碼、稽核 originType、原生 App opt-in 範圍），並已回填 CLAUDE.md / db-schema.md / api-contract.md / infra/sql；devops-engineer 已完成 LocalDB 重新驗證含第七張表的 schema，dev-engineer 已完成 provider 動態 origin 解析與 server 端 origin 允許清單/JPA 實體。PoC harness 程式碼已用 Gradle product flavor（`prod`/`poc`）分離，正式建置產物不含診斷程式碼，此開放問題已解決。

購物網站串接參考範例（`shopping-site-reference/`）已建立，示範註冊/登入/裝置管理三個代理流程，核心為 `FidoSessionJwtValidator`（不信任回應 `verified` 欄位，只信任自行驗證過的 session JWT）。過程中發現的 IDOR 缺口（`externalUserId` 未限定為呼叫端已驗證的登入使用者）已修復：範例現在一律從購物網站自己的登入 session 取得 `externalUserId`，並已將此責任邊界明文寫入 `docs/api-contract.md`（D15）。

`fido-server` 與 `shopping-site-reference` 之間已完成**真實跨行程端對端整合驗證**（qa-engineer 執行，人工複核 log 與 git 狀態）：兩個服務各自以獨立 JVM 啟動（非單一測試行程內的 `MockMvc`/`@SpringBootTest`），以真實 HTTP 走完「註冊（真實 StrongBox 等級 android-key attestation 憑證鏈+簽章）→ 裝置落庫確認 → 登入（真實 assertion 簽章）→ session JWT 簽發 → shopping-site-reference 端真實打 JWKS 端點驗證簽章並建立 SHOP_SESSION → 裝置列表/撤銷代理 → IDOR 反例（夾帶他人 externalUserId 應回 403 EXTERNAL_USER_ID_MISMATCH）」全流程，共 13 項檢查全數通過，證據與重現腳本見 `fido-server/src/test/java/com/fido/server/e2e/CrossProcessE2EManualRunner.java`（手動執行，未掛進 `mvn test`/CI）。Android Credential Provider 端因本機環境無已連接的模擬器/實機，此輪不含在範圍內，其結果仍以既有的條件式通過 PoC 紀錄為準。

### 啟動器畫面決策（`android-credential-provider` prod flavor 是否需要 launcher UI）

PoC 用 Gradle product flavor 把診斷 harness（含 `HarnessActivity`，唯一帶 `MAIN`/`LAUNCHER` 的元件）隔離到 `poc` flavor 後，`prod` flavor 變成**零啟動器 Activity**——純背景 `CredentialProviderService`。此為移除 harness 的副作用而非刻意設計，遺留為開放問題，由 systems-analyst 分析、人類拍板。結論：

- **三件事在既有架構下已明確不需要 app 端畫面**，headless 對它們是正確答案：
  1. **註冊/登入 ceremony 都不需直接開啟 app**——新裝置第一次註冊與後續登入走同一條路：購物網站（瀏覽器頁面或其原生 App）呼叫 Credential Manager `createCredential`/`getCredential`，由系統以 PendingIntent 隱式啟動 `CreatePasskeyActivity`/`GetPasskeyActivity`（見 `CreatePasskeyActivity.kt` 以 `PendingIntentHandler.retrieveProviderCreateCredentialRequest` 取請求），程式碼中沒有任何「使用者主動開 app 開始註冊」的路徑。
  2. **`多裝置…提供管理介面`需求已被雙重滿足**：系統 Settings → 密碼/憑證提供者頁面（任何 `CredentialProviderService` 免費取得）＋ 購物網站自家的 `DeviceProxyController`（`/shop/api/fido/devices` list/revoke，經購物網站已驗證 session 代理）。Android app **不需**自建裝置列表/撤銷 UI。
  3. **「目前使用哪個身分」指示器不適用**：本 app 不持有任何帳號/登入狀態，只依 `rpId` 存放 platform credential，無可顯示內容。

- **唯一未被既有決策決定的開放判斷點是「首次啟用 provider ＋ app 可被發現性」**：Android 14 新裝的 `CredentialProviderService` 不會自動啟用，使用者須到系統設定手動開啟；零啟動器時 app 不在 app drawer 出現，安裝後無處可點、無法深連結到設定、無法顯示「是否已啟用」。人類拍板採 **Option B**：保留**一個**最小「設定/狀態」啟動器 Activity（啟用狀態顯示＋深連結進系統設定的按鈕＋版本/客服/隱私文字），與 `非獨立 APP 跳轉` 決策相容（該決策管的是認證流程不得改走獨立 app，而非禁止設定畫面）。**硬性範圍邊界**：此畫面永遠不得長成任何登入/註冊 ceremony 或裝置管理 UI，一旦如此即違反 `非獨立 APP 跳轉`。

實作（把此 Activity 加進 `prod` 的 `src/main/AndroidManifest.xml` 並建畫面）由 dev-engineer 在其 OriginResolver/瀏覽器允許清單當前任務結束後另案承接，避免兩個 agent 同時建置同一 Gradle 專案；systems-analyst 本次僅落定決策文件、未動 Android 程式碼與 manifest。

## 待辦事項

- `shopping-site-reference/`：`externalUserId` 目前保留為選填相容欄位（用於示範「夾帶不同值即拒絕」），評估是否要直接從請求 DTO 移除，讓這個攻擊面在型別層級就不存在
- `shopping-site-reference/`：尚未實作 CSRF 防護（狀態變更端點僅靠 `SameSite=Lax` cookie），示範範例是否要一併補上正確模式待評估
- `shopping-site-reference/`：session cookie 目前 `secure=false`（方便本機 HTTP 測試），正式部署（全站 TLS）需要改為 `true`
- `android-credential-provider/`（dev-engineer 待接）：依「啟動器畫面決策」的 Option B，在 `prod` flavor 的 `src/main/AndroidManifest.xml` 新增一個最小「設定/狀態」啟動器 Activity 並建畫面（啟用狀態＋深連結系統設定按鈕＋版本/客服/隱私文字）；嚴守範圍邊界，不得含任何 ceremony 或裝置管理 UI。待其 OriginResolver/瀏覽器允許清單任務結束後另案進行

## 團隊分工（Claude Code Subagents）

本專案設定四個 project-level subagent（`.claude/agents/`），對應軟體開發流程中的四個角色：

- **systems-analyst**：架構決策、規格釐清、設計文件（API 合約、DB schema、流程圖）
- **dev-engineer**：Spring Boot 後端、Android Credential Provider APP、購物網站串接程式碼
- **devops-engineer**：SQL Server 建置、TDE/備份、部署腳本、CI
- **qa-engineer**：測試撰寫與執行、對照驗收標準回報結果

任何新的架構決策一旦拍板，請更新這份 CLAUDE.md，讓四個 subagent 在下次被呼叫時都能讀到一致的上下文（每次呼叫都是全新 context，不會記得先前對話）。
