# 維護手冊

> 適用對象：採用廠商的維運 / SRE 團隊，負責 `fido-server` 與其 SQL Server 資料庫上線後的日常運維。
>
> 部署與初次建置見 [`environment-setup-guide.md`](environment-setup-guide.md)；錯誤碼定義見 [`api-integration-guide.md`](api-integration-guide.md)；能力邊界見 [`technical-limitations.md`](technical-limitations.md)。

---

## 1. 維運對象一覽

| 對象 | 內容 | 對應建置腳本 |
|---|---|---|
| `fido-server` 應用 | Spring Boot jar，無狀態（唯一的行程內狀態是 session JWT 簽章金鑰，見第 4 節） | — |
| SQL Server `FidoServerDb` | 九張核心表，TDE 全庫加密 | `001`–`004` |
| 備份 Agent Job | 完整 / 差異 / 交易記錄備份 | `005` |
| 清理 Agent Job | challenge 過期標記 / 清除、`audit_log` 1 年保留清理 | `006` |

---

## 2. 備份 / 還原

備份策略由 `infra/sql/005_scheduled_backups.sql` 建立的三個 SQL Server Agent Job 提供（對齊 CLAUDE.md「TDE 全庫加密 + 標準定期備份」）：

| Job 名稱 | 頻率 | 內容 |
|---|---|---|
| `FidoServerDb - Full Backup` | 每週日 01:00 | 完整備份，`WITH COMPRESSION, CHECKSUM` |
| `FidoServerDb - Differential Backup` | 每日 02:00 | 差異備份 |
| `FidoServerDb - Log Backup` | 每 30 分鐘 | 交易記錄備份（RPO 上限約 30 分鐘） |

### 2.1 首次啟用時的關鍵動作

- 資料庫設為 FULL 復原模式後，**第一次交易記錄備份必須晚於第一次完整備份**，否則記錄備份會失敗（LSN 鏈未建立）。建置完 `001`–`004` 後，須**立即手動觸發一次 `FidoServerDb - Full Backup`**（或手動 `BACKUP DATABASE`），再讓排程接手。
- 確認 SQL Server Agent 服務為「執行中」且自動啟動，否則所有排程都不會觸發。

### 2.2 還原鏈

還原順序：**最近一次完整備份 → 最近一次差異備份（如有）→ 其後所有交易記錄備份**，依序 RESTORE。

### 2.3 備份檔異地與清理

- 備份目的地建議與資料庫檔案分放不同實體磁碟 / 儲存裝置，並另行同步一份到異地 / 異機（`005` 未含異地同步，須另訂機制）。
- **舊備份檔清理未包含在 `005` 內**（`005` 檔尾僅提供建議與 `xp_delete_file` 範例，且該擴充預存程序未公開文件化）。建議自訂保留策略（例如 FULL 保留 5 份、DIFF 保留 14 天、LOG 保留 8 天），以 Maintenance Plan 或 OS 層排程（PowerShell / robocopy）實作，避免依賴未公開行為。

### 2.4 還原演練

建議每季（或每次 TDE 憑證輪替後）在測試機演練一次完整還原，確認備份「真的能還原」而非只是「有跑成功」。

---

## 3. TDE（全庫加密）

- TDE 由 `004_enable_tde.sql` 啟用（AES_256 DEK，以 master 的 TDE 憑證保護）。
- **憑證與私鑰遺失 = 加密資料庫永遠無法還原 / 附加。** 這是 TDE 最大的運維風險。
- 運維注意事項：
  1. `004` 產生的 `.cer` / `.pvk` 必須異地備份，密碼與憑證分開保管（詳見環境建置手冊 §3.2）。
  2. 憑證有到期日（`004` 範例為 `2036-12-31`）。**建立到期前的輪替 / 更新提醒排程**，避免憑證過期。
  3. 若把資料庫還原到「另一台」SQL Server，須先在目的地用同一份 `.cer` + `.pvk` + 密碼還原憑證，否則無法附加（會出現 "cannot find server certificate" 類錯誤）。
- 確認備份 Job 的完整 / 差異 / 記錄備份都在 TDE 啟用之後產生，還原時才能正常解密。

---

## 4. Session JWT 簽章金鑰輪替

### 4.1 v1.0.0 現況

`fido-server` 的 session JWT 簽章金鑰（EC P-256 / ES256）**持久化於資料庫 `signing_keys` 表**，並透過 `GET /api/v1/.well-known/jwks.json` 發布對應公鑰。這代表：

- **程序重啟不再更換金鑰**：啟動時載入資料庫內既有的 `ACTIVE` 金鑰；只有全新資料庫（從未有任何金鑰）首次啟動才會自動產生一把並存入。
- **多實例天然共享同一把金鑰**：所有連到同一個資料庫的 `fido-server` 實例，載入的都是同一把 `ACTIVE` 金鑰，跨實例簽發/驗證一致，**貴公司不需要自行實作任何金鑰共享機制**。
- 私鑰以 PKCS#8 格式存 `VARBINARY`，由資料庫的 TDE 全庫加密保護，未額外做應用層封裝。

### 4.2 手動輪替（`rotate-signing-key`）

v1 **沒有自動排程輪替**（因 session JWT 僅 120 秒效期，重疊窗口需求極小，屬刻意的範圍邊界），但提供平台維運方（賣方）在伺服器主機上執行的本機 admin CLI 手動輪替指令：

```
java -jar fido-server.jar --spring.profiles.active=admin-cli --fido.admin.command=rotate-signing-key
```

執行後：現有 `ACTIVE` 金鑰轉為 `RETIRED`（記錄 `retired_at`），產生一把新的 `ACTIVE` 金鑰。JWKS 端點會**同時發布 ACTIVE + RETIRED 兩把公鑰**，過渡期內用舊金鑰簽出、尚未過期（≤120 秒）的 JWT 仍可正常驗簽，不會中斷正在進行的登入交接。適用情境：疑似金鑰外洩、例行性金鑰更新政策等。此 CLI 不開任何網路端口，執行完即結束（無需擔心程序常駐）。

### 4.3 運維建議

- **貴公司後端應對 JWKS 快取設較短 TTL**（參考範例用 300 秒），並在驗簽失敗時重新拉取 JWKS，以便在手動輪替後盡快取得新公鑰。
- `signing_keys` 表為組態性資料，正常只會有一把 `ACTIVE`；輪替產生的 `RETIRED` 列可在確認無驗簽需求後（建議等待遠超過 120 秒的安全緩衝，例如數小時）由維運人員視需要手動清理，非必要動作。

---

## 5. 稽核紀錄保留與清理排程

- 稽核事件寫在 `audit_log` 表（不是應用日誌），保留 **1 年**。事件類型包含 `REG_SUCCESS`、`AUTH_SUCCESS`、`AUTH_FAIL`、`AUTO_REVOKE_COUNTER_REGRESSION`、`DEVICE_REVOKED_BY_USER`、`DEVICE_REVOKE_NOOP`，以及跨裝置 QR 登入（情境三）的 `XDEV_SESSION_CREATED`、`XDEV_CLAIMED`、`XDEV_CONFIRMED`、`XDEV_CONSUMED` 等。跨裝置事件的 `detail` JSON 內含 `originType='CROSS_DEVICE_QR'` 與 `proximityMismatch`（true/false）。
- 清理由 `006_retention_cleanup_jobs.sql` 建立的三個 Agent Job 處理：

| Job 名稱 | 頻率 | 動作 |
|---|---|---|
| `FidoServerDb - Expire Auth Challenges` | 每 1 分鐘 | 把逾期仍為 `PENDING` 的 `auth_challenges` 標記為 `EXPIRED` |
| `FidoServerDb - Purge Old Auth Challenges` | 每日 03:15 | 批次刪除 `created_at` 超過 1 天的 `auth_challenges`（非稽核來源） |
| `FidoServerDb - AuditLog Retention Cleanup` | 每日 03:30 | 批次刪除 `created_at` 超過 365 天的 `audit_log`（對齊 1 年保留） |

- 兩個清理 Job 採每批 5,000 列的批次 DELETE、批次間 `WAITFOR DELAY`，避免大交易鎖表。中小規模（數萬會員、峰值 ≤100 TPS）下足夠；若稽核量遠超預期、DELETE 耗時開始影響營運，再評估改採月分割表方案（屬架構調整，須先評估）。
- 運維應定期確認這三個 Job 有正常執行（未失敗、未停用）。

---

## 6. 常見錯誤代碼排查對照表

以下對照 `fido-server` 回傳的錯誤碼，供維運與 L2 支援快速定位。錯誤碼定義見 API 串接手冊第 5 節。

| code | HTTP | 常見根因 | 排查方向 |
|---|---|---|---|
| `UNAUTHENTICATED` | 401 | API Key 缺失 / 錯誤 / 已停用 | 確認呼叫端 `X-API-Key`；確認租戶 `status=ACTIVE` |
| `TENANT_MISMATCH` | 403 | `X-Tenant-Id` 與 API Key 對不上 | 呼叫端 header 設定錯誤 |
| `TENANT_DISABLED` | 403 | 租戶被停用 | 查 `tenants.status` |
| `RP_ID_MISMATCH` | 403 | 前端 rp.id 與租戶 `rp_id` 不符 | 確認前端 WebAuthn rp.id = 貴公司網域 = `tenants.rp_id` |
| `ORIGIN_NOT_ALLOWED` | 403 | `clientDataJSON.origin` 不在允許清單 | 檢查 `tenants.expected_origin`；原生 App 檢查 `tenant_app_bindings` 是否已登錄且 `ACTIVE` |
| `CHALLENGE_EXPIRED` / `CHALLENGE_NOT_FOUND` | 400 | 使用者停留過久 / 網路延遲 / ceremonyId 錯 | 正常屬使用者重試即可；若大量出現，查前後端延遲與時鐘 |
| `ATTESTATION_INVALID` | 422 | attestation 解析 / 簽章失敗 | 前端傳回資料損毀，或非本平台支援的 authenticator |
| `ATTESTATION_CHAIN_INVALID` | 422 | 憑證鏈未鏈到 Google root | 裝置無法通過硬體憑證鏈（非正規 / 改機裝置）；確認 `poc-trust.enabled=false` 未誤開 |
| `HARDWARE_SECURITY_NOT_MET` | 422 | 裝置未達 TEE/StrongBox | 該裝置硬體不符，屬預期拒絕 |
| `ASSERTION_INVALID` | 422 | 登入簽章驗證失敗 | 資料損毀 / 憑證與金鑰不符 |
| `SIGN_COUNTER_REGRESSION` | 422 | sign counter 倒退，已自動撤銷 | **安全訊號**：疑似金鑰複製 / 仿冒。查 `audit_log` 的 `AUTO_REVOKE_COUNTER_REGRESSION`，若頻繁需資安介入 |
| `CREDENTIAL_REVOKED` | 422 | 用已撤銷憑證登入 | 屬預期；引導使用者用帳密登入並重新註冊 |
| `RATE_LIMITED` | 429 | 超過該租戶 TPS 上限 | 查是否異常流量 / 攻擊；必要時調整 `tenants.rate_limit_tps` |
| `INTERNAL_ERROR` | 500 | 伺服器內部錯誤 | 查 `fido-server` 應用日誌，以 `traceId`（= `X-Request-Id`）追蹤 |

排查時可用 `X-Request-Id` / 回應的 `traceId` 串起「應用日誌 ↔ `audit_log.request_id`」，跨層追同一次請求。

---

## 7. 監控建議

以下訊號建議納入監控與告警：

| 訊號 | 意義 | 建議告警條件 |
|---|---|---|
| `/actuator/health` 非 `UP` | 服務不可用 | 立即告警 |
| `429 RATE_LIMITED` 觸發率 | 速率限制被打到 | 短時間大量觸發 → 疑似異常流量 / 攻擊，或需調整 TPS |
| challenge 逾期率（`CHALLENGE_EXPIRED` 比例） | 使用者完成 ceremony 前逾時的比例 | 明顯上升 → 前後端延遲 / 網路 / 時鐘問題 |
| 異常自動撤銷率（`AUTO_REVOKE_COUNTER_REGRESSION`） | sign counter 倒退次數 | 上升 → **安全可疑訊號**，可能有金鑰複製嘗試，資安應介入 |
| `ATTESTATION_CHAIN_INVALID` / `HARDWARE_SECURITY_NOT_MET` 比例 | 大量裝置無法通過硬體驗證 | 突增 → 可能是特定 OEM 相容性問題（見技術限制手冊 OEM 覆蓋）或設定誤改 |
| 跨裝置 QR 登入 proximity 不符率（`audit_log.detail.proximityMismatch=true` 佔 `XDEV_CONFIRMED` 比例） | 桌機/手機出口 IP 不一致的比例（見第 11 節） | 明顯偏高 → 可能是正常誤判（行動網路 vs Wi-Fi）也可能是中繼攻擊；短時間單一租戶/使用者暴增 → 安全可疑，資安評估 |
| `INTERNAL_ERROR`（500）率 | 伺服器內部異常 | 任何持續 500 → 查日誌 |
| SQL Server Agent Job 狀態 | 備份 / 清理 Job 是否失敗或停用 | Job 失敗 → 告警（尤其備份 Job） |
| 資料庫連線 / 交易記錄檔成長 | DB 健康度 | 記錄檔異常成長 → 查交易記錄備份是否正常 |

稽核事件（`audit_log`）可作為安全分析來源，特別留意 `AUTO_REVOKE_COUNTER_REGRESSION` 與短時間內同一使用者大量 `AUTH_FAIL`。

---

## 8. 容量規劃基準

v1 設計目標為**中小規模**（對齊 CLAUDE.md）：

| 指標 | 基準 |
|---|---|
| 會員規模 | 數萬會員 |
| 峰值吞吐 | ≤ 100 TPS（每租戶預設速率上限亦為 100 TPS） |
| 部署形態 | 全地端部署（非雲端） |

- 這個規模下，SQL Server 資料量成長平緩，備份與清理排程的預設頻率足夠。
- 若貴公司預期顯著超過此規模（例如遠高於 100 TPS 或百萬級會員），須先評估：多實例部署下的 JWT 簽章金鑰共享（第 4 節）、速率上限調整、`audit_log` 是否改用分割表清理、資料庫資源（CPU / IO / 記憶體）擴充。超出此容量目標屬架構調整範疇。

---

## 9. 版本升級注意事項

- **schema 變更以 `infra/sql/002` 為權威**：`fido-server` 的 `spring.jpa.hibernate.ddl-auto=validate`，只驗證 entity 與既有 schema 是否一致、**不會自動改 schema**。升級若涉及 schema 變更，須先套用對應的 DDL 遷移腳本，再啟動新版應用；否則 `validate` 會在啟動時報結構不一致而拒絕啟動。
- **升級前務必先做完整備份**（並確認可還原）。
- **設定檔審查**：升級後重新核對環境建置手冊第 8 節的部署檢查清單，特別是 `fido.attestation.mode=real`、`poc-trust.enabled=false`、`dev-seed.enabled=false` 這幾項安全開關沒有被新版預設值或設定合併覆蓋回不安全狀態。
- **JWKS / JWT 相容性**：升級重啟會更換簽章金鑰（第 4 節），滾動升級期間要留意正在交接的 session；建議在低峰執行。
- **驗證**：升級後跑一次註冊 → 登入 → 撤銷的冒煙測試，並確認 `/actuator/health`、JWKS 端點正常。

---

## 10. 日常運維檢查清單

- [ ] 每日確認備份 Job（完整 / 差異 / 記錄）皆成功
- [ ] 每日確認清理 Job（challenge / audit_log）皆成功
- [ ] 定期確認交易記錄檔未異常成長（記錄備份正常）
- [ ] 監控 429 / challenge 逾期率 / 異常自動撤銷率
- [ ] TDE 憑證到期提醒已設定，`.cer`/`.pvk` 異地備份完好
- [ ] 每季演練一次還原
- [ ] 安全開關（attestation real / poc-trust off / dev-seed off）維持正確
- [ ] （若已啟用跨裝置 QR 登入）監控 proximity 不符率（第 11 節）

---

## 11. 跨裝置 QR 登入（情境三）的 proximity 警示與維運

> 對應能力說明技術限制手冊第 2.2 節、串接手冊第 11 節、合約 `../api-contract.md` §3.4。**實作狀態**：架構已拍板、規格已定，功能尚在實作中；本節為上線後維運指引，功能到位後適用。

### 11.1 proximity 檢查是「只警示、不阻擋」（平台決策 S2）

- 跨裝置 QR 登入時，`fido-server` 會比對「桌機瀏覽器出口 IP」與「手機直連出口 IP」是否一致（proximity 檢查）。
- **平台拍板採警示制**：兩者不一致時**不會擋下登入**，只在 `audit_log`（`detail.proximityMismatch=true`）與回應中標記異常，登入照常完成。
- 這代表 proximity **不能被當成阻擋遠端中繼攻擊的機制**——它只提供事後可追蹤的訊號。防釣魚的實際保護，主要落在「使用範圍限縮（敏感操作要求 step-up）」這一層（串接手冊第 11.3 節）。

### 11.2 維運人員可以做什麼

- **追蹤異常頻率**：以 `audit_log` 查詢 `event_type='XDEV_CONFIRMED'` 且 `detail.proximityMismatch=true` 的比例與趨勢（依租戶、依使用者）。偶發不符多屬正常誤判（手機走行動網路、桌機走 Wi-Fi；企業/校園多重出口 NAT）。
- **辨識可疑樣態**：單一使用者短時間內大量 proximity 不符、或某租戶不符率異常飆高，應升級給資安評估是否有中繼攻擊嘗試。
- **協助個別租戶**：若某租戶回報其使用者族群誤判率過高造成困擾，可協助檢視其網路拓撲、或（見下）評估未來的每租戶策略調整。

### 11.3 後續可擴充項（本版本未實作）

- 目前 proximity 政策為**全平台一致的警示制**，**沒有**每租戶可切換為 strict（不符即拒）的設定機制。
- 若未來有租戶明確要求更強防護、且能接受其使用者族群的誤判率，可評估擴充為「**每租戶可設定 proximity 政策（warn / strict）**」。此為 systems-analyst 標記的後續可擴充方向，**本版本刻意不實作**（擁有者本次選定全平台警示制）。需要時請提報平台營運方走架構調整流程，不要在維運層自行以資料庫改動模擬此行為。
