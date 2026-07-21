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

可行性評估與 22 項規格缺口已全數確認。下一步：Android Credential Provider 技術驗證 PoC（2 週，10 項驗證項目，#1–5 為關鍵項目），由資深工程師執行中。PoC 通過後才進入正式開發時程。

## 團隊分工（Claude Code Subagents）

本專案設定四個 project-level subagent（`.claude/agents/`），對應軟體開發流程中的四個角色：

- **systems-analyst**：架構決策、規格釐清、設計文件（API 合約、DB schema、流程圖）
- **dev-engineer**：Spring Boot 後端、Android Credential Provider APP、購物網站串接程式碼
- **devops-engineer**：SQL Server 建置、TDE/備份、部署腳本、CI
- **qa-engineer**：測試撰寫與執行、對照驗收標準回報結果

任何新的架構決策一旦拍板，請更新這份 CLAUDE.md，讓四個 subagent 在下次被呼叫時都能讀到一致的上下文（每次呼叫都是全新 context，不會記得先前對話）。
