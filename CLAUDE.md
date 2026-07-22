# FIDO/FIDO2 認證系統 — 專案共識

四個系統：FIDO 驗證伺服器、FIDO 驗證 APP（Android）、採用 FIDO 登入的購物網站、資料主機（SQL Server）。
後端：Spring Boot + Maven。架構情境：**A（標準 WebAuthn，同裝置）**，情境 B（跨裝置推播）已放棄。

## 已確認的關鍵架構決策

| 主題 | 決策 |
|---|---|
| FIDO 伺服器定位 | 多租戶平台，僅作後端驗證服務（非身分來源） |
| RP ID | 購物網站網域 |
| 身分權威來源 | 購物網站既有帳密系統；FIDO 為加掛選項，帳密永久保留（不可單獨用 FIDO 登入取代帳密救援） |
| FIDO 驗證 APP 角色 | 自訂 Android `CredentialProviderService`，掛載於系統 Credential Manager（非獨立 APP 跳轉、非推播） |
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
| Challenge 時效 | 60 秒，逾時前端自動重新申請 |
| 資料庫 | 獨立 SQL Server 實例（與其他系統分開）；六張核心表：`tenants`, `fido_user_ref`, `fido_credentials`, `bound_devices`, `auth_challenges`, `audit_log` |
| 加密/備份 | TDE 全庫加密 + 標準定期備份 |
| 容量目標 | 中小規模：數萬會員，峰值 ≤100 TPS |
| 部署 | 全地端部署（非雲端） |
| 法規範圍 | 適用台灣個資法，不涉金流相關法規 |
| 稽核紀錄保留 | 1 年 |
| FIDO Conformance Testing | 不在第一版投入，上線後再評估 |

## 目前階段

可行性評估與 22 項規格缺口已全數確認。REST API 合約（`docs/api-contract.md`）與六張核心表 DB schema（`docs/db-schema.md`）已定案；`fido-server` 後端骨架已建立，含真實 WebAuthn 密碼學驗證（簽章驗證、Android Key Attestation 憑證鏈驗證、TEE/StrongBox 判讀）、完整測試涵蓋率、JPA/Hibernate 持久層（本機開發用 H2，正式部署接 SQL Server，`infra/sql/` 已備妥建置腳本待正式環境套用）。

Android Credential Provider 技術驗證 PoC 的 10 項驗證項目已具體定案，見 `docs/android-poc-checklist.md`（含每項的關鍵/非關鍵標記、模擬器可驗證/須實機驗證標註、通過判準）。與原本「#1–5 為關鍵項目」的粗略說法相比，定案後的關鍵項目改為聚焦「地基掛載 + 硬體金鑰 + android-key 格式對接」（清單項目 1–5），**登入 ceremony 端對端重新歸類為非關鍵**（assertion 簽章與硬體無關、伺服器端已有測試覆蓋）。

開發環境目前沒有實體 Android 裝置，僅能用模擬器（Android 14 / API 34）驗證；PoC「通過」採**條件式通過（pending 實機）**：模擬器可驗證項目全數通過、關鍵風險已排除，即可先行推進正式開發時程，硬體聲明真偽與 OEM 相容性列為取得實機後的收尾驗證項目，不阻塞整體進度。

**PoC 執行完畢，結論：條件式通過（pending 實機）。** `android-credential-provider/` 專案已建立（`FidoCredentialProviderService` + StrongBox/TEE 金鑰產生 + 手寫 CBOR 組出 `fmt=android-key` attestationObject），關鍵項目 1–5 在模擬器上全數驗證通過，其中風險最高的項目 3（自訂 provider 能否產出伺服器可解析的 android-key 格式）已用**模擬器真實 Android Keystore 吐出的憑證鏈**（非僅 JVM 自簽測試 fixture）端對端送驗 `fido-server`（`fido.attestation.mode=real`）確認可行。硬體安全等級本身（StrongBox/TEE 是否真的達標）因模擬器無真實安全硬體，仍待實體裝置驗證；`fido-server` 新增 `fido.attestation.poc-trust.*` 設定（預設關閉、僅供 PoC 期間額外信任模擬器測試 root，不影響正式路徑的 Google root 信任集合）。詳細逐項結果見執行紀錄；已知待釐清的開放問題（原生 App 情境下的 origin 綁定架構、PoC harness 程式碼是否要移出正式 APK）待 systems-analyst 後續處理。

## 團隊分工（Claude Code Subagents）

本專案設定四個 project-level subagent（`.claude/agents/`），對應軟體開發流程中的四個角色：

- **systems-analyst**：架構決策、規格釐清、設計文件（API 合約、DB schema、流程圖）
- **dev-engineer**：Spring Boot 後端、Android Credential Provider APP、購物網站串接程式碼
- **devops-engineer**：SQL Server 建置、TDE/備份、部署腳本、CI
- **qa-engineer**：測試撰寫與執行、對照驗收標準回報結果

任何新的架構決策一旦拍板，請更新這份 CLAUDE.md，讓四個 subagent 在下次被呼叫時都能讀到一致的上下文（每次呼叫都是全新 context，不會記得先前對話）。
