# 環境建置手冊

> 適用對象：採用廠商的維運 / 基礎設施團隊，負責在自有環境部署 `fido-server` 與其專屬 SQL Server 資料庫。
>
> 本手冊說明 v1.0.0 的部署前置需求、資料庫建置、設定檔鍵值、初始租戶開通、健康檢查與日誌位置。API 串接請另見 [`api-integration-guide.md`](api-integration-guide.md)，已知限制請務必先讀過 [`technical-limitations.md`](technical-limitations.md)。

---

## 1. 架構總覽（部署視角）

本平台需由採用廠商自行部署的元件只有兩個：

| 元件 | 說明 | 由誰部署 |
|---|---|---|
| **`fido-server`** | FIDO2 驗證伺服器（Spring Boot 3 / Java 21）。對外提供 REST API。 | 採用廠商 |
| **SQL Server 資料庫** | `fido-server` 專用的獨立資料庫實例（七張核心表），與貴公司其他系統的資料庫分開。 | 採用廠商 |

其餘兩個元件**不需要**採用廠商部署：

- **`android-credential-provider`**：這是提供給終端使用者安裝在手機上的 Android App（FIDO 驗證器），由平台營運方發布，不在採用廠商的伺服器部署範圍。
- **`shopping-site-reference`**：這是「如何串接」的教學參考範例，**不是要交付上線的產品程式碼**。貴公司自建的購物網站後端請參考它的串接模式（見 API 串接手冊），不要直接部署它。

> 重要責任邊界：`fido-server` 只是後端「驗證服務」，**不是身分權威來源**。貴公司既有的帳號密碼系統仍是身分權威；FIDO 是加掛的強化選項。詳見技術限制手冊第 10 節。

---

## 2. 系統需求

### 2.1 `fido-server` 執行環境

| 項目 | 需求 |
|---|---|
| Java | **JDK 21**（`fido-server` 以 `<java.version>21</java.version>` 建置，較舊的 JRE 無法執行） |
| 記憶體 | 中小規模（數萬會員、峰值 ≤100 TPS）下，建議至少 2 GB heap 起跳，依實際壓測調整 |
| 建置工具 | Maven 3.9+（若由貴公司自行從原始碼建置 jar） |
| 作業系統 | 任何可執行 JDK 21 的 OS（本平台為全地端部署設計，非雲端託管） |

### 2.2 SQL Server

| 項目 | 需求 |
|---|---|
| 版本 | **建議 SQL Server 2019 Standard/Enterprise 以上**。TDE（全庫加密）自 SQL Server 2019 起 Standard Edition 亦支援；2019 之前僅 Enterprise 支援。部署前務必確認目標版本是否支援 TDE。 |
| 復原模式 | FULL（建置腳本 `001` 會設定，配合交易記錄備份縮短 RPO） |
| SQL Server Agent | 必須為「執行中」且設定為自動啟動，否則備份與清理排程（`005`/`006`）不會觸發 |
| 磁碟配置 | 建議資料檔（.mdf）與交易記錄檔（.ldf）分放不同實體磁碟 |

> **重大提醒（不可隱瞞的部署風險）**：`infra/sql/` 六支建置腳本目前**僅在 H2 記憶體模式與本機 LocalDB 驗證過 schema 建置正確性，尚未在真正的正式 SQL Server 環境端對端跑過完整驗證**。腳本內的磁碟路徑、密碼、`sa` 登入名稱、相容性層級皆為範例佔位值。採用廠商在正式 SQL Server 上首次套用時，須自行承擔逐項驗證與調整的責任，強烈建議先在測試庫完整演練一次（含備份還原演練）再上正式環境。此限制亦記於技術限制手冊第 9 節。

### 2.3 網路 / 防火牆

| 項目 | 說明 |
|---|---|
| 對外 API port | `fido-server` 預設監聽 **`8443`**（`application.yml` 的 `server.port`）。貴公司購物網站後端須能以 server-to-server 方式連到此 port。 |
| TLS | 全站強制 TLS（見 API 合約 §1.1）。**注意：`fido-server` 應用層本身並未強制 TLS**（`ApiKeyAuthFilter` 沒有 `isSecure()` 檢查），TLS 應由部署層負責——建議在 `fido-server` 前方架設反向代理（如 Nginx / IIS ARR）或負載平衡器終結 TLS，或另行設定 `server.ssl.*`。正式環境絕不可讓 API 走純 HTTP。 |
| 資料庫連線 | `fido-server` → SQL Server（預設 TCP 1433），連線字串預設 `encrypt=true`。此連線應限制在內網、以防火牆限制來源。 |
| 公開端點 | 只有 `GET /api/v1/.well-known/jwks.json`（JWKS 公鑰）與 `/actuator/*`（健康檢查）不需 API Key。JWKS 需可被貴公司後端讀取以驗證 session JWT 簽章；`/actuator` 建議僅開放內網監控來源。 |

---

## 3. 資料庫建置：`infra/sql/` 六支腳本

以下六支腳本位於 `infra/sql/`，**必須依編號順序執行**。前四支對 `FidoServerDb` 建立結構與加密，後兩支建立 SQL Server Agent 排程 Job。

| 順序 | 腳本 | 用途 | 執行對象 |
|---|---|---|---|
| 1 | `001_create_database.sql` | 建立 `FidoServerDb` 資料庫、設定 FULL 復原模式、`READ_COMMITTED_SNAPSHOT ON` 等資料庫層級設定 | 對「SQL Server 執行個體」執行（資料庫尚未存在） |
| 2 | `002_create_tables.sql` | 建立七張核心表（依外鍵相依順序：`tenants` → `fido_user_ref` → `fido_credentials` → `bound_devices` → `auth_challenges` → `audit_log` → `tenant_app_bindings`），含 PK/UNIQUE/CHECK/FK | 對 `FidoServerDb` 執行 |
| 3 | `003_create_indexes.sql` | 建立各表的非唯一次要索引 | 對 `FidoServerDb` 執行 |
| 4 | `004_enable_tde.sql` | 啟用 TDE 全庫加密（建立 master key、TDE 憑證、DEK、`ENCRYPTION ON`），並**立即備份憑證與私鑰** | 需 sysadmin |
| 5 | `005_scheduled_backups.sql` | 建立三個備份 Agent Job：完整備份（每週日 01:00）、差異備份（每日 02:00）、交易記錄備份（每 30 分鐘） | 對 `msdb` 執行，需 SQL Agent |
| 6 | `006_retention_cleanup_jobs.sql` | 建立三個清理 Agent Job：challenge 過期標記（每分鐘）、清除逾期 challenge（每日 03:15）、`audit_log` 保留 1 年清理（每日 03:30） | 對 `msdb` 執行，需 SQL Agent |

### 3.1 執行前必做的替換

以下腳本內容皆為**範例佔位值**，套用到正式環境前務必替換：

- `001`：資料檔 / 記錄檔的磁碟路徑（範例為 `D:\SQLData\...` / `E:\SQLLog\...`）、相容性層級（範例 `150` = SQL Server 2019，2022 應改 `160`）。**目標資料夾須先手動建立**，否則 `CREATE DATABASE` 會失敗。
- `004`：`@MasterKeyPassword`、`@PvkPassword`（範例 `REPLACE_WITH_...`）、憑證備份路徑、憑證到期日（範例 `2036-12-31`）。
- `005` / `006`：備份目的地路徑、`@owner_login_name`（範例 `sa`；若貴公司已停用 / 改名 `sa`，須改成實際存在的 sysadmin 登入，否則 `sp_add_job` 會失敗）。

### 3.2 TDE 憑證的關鍵注意事項（資料能不能救回來的關鍵）

`004` 執行後會產生 TDE 憑證（`.cer`）與私鑰（`.pvk`）。**憑證與私鑰一旦遺失，加密的資料庫將永遠無法還原或附加**。務必：

1. 將 `.cer` 與 `.pvk` 複製到「與資料庫伺服器不同」的安全儲存位置（異地保險櫃 / 金鑰保管系統）。
2. 私鑰密碼與主金鑰密碼與憑證檔案分開保管。
3. 若日後要把資料庫還原到「另一台」SQL Server（災難復原 / 遷移），必須先在目的地用同一份 `.cer` + `.pvk` + 密碼還原憑證，否則無法附加。
4. 建議每季或每次憑證輪替後，在測試機演練一次「用備份憑證還原資料庫」。

### 3.3 建立完後的首次備份

`001` 把資料庫設為 FULL 復原模式後，在「第一次完整備份」執行之前，交易記錄備份會失敗（LSN 鏈未建立）。因此執行完 `001`–`004` 後，請**立即手動觸發一次 `FidoServerDb - Full Backup` Job（或手動 `BACKUP DATABASE`）**，再讓排程接手。詳見維護手冊第 2 節。

---

## 4. 關鍵設定檔鍵值（`application.yml`）

`fido-server` 的設定集中在 `fido-server/src/main/resources/application.yml`。以下是採用廠商部署時必須理解與調整的鍵值。**正式環境的密碼、API Key 不應寫回此檔並進版控**，建議以環境變數或外部設定覆寫。

### 4.1 伺服器與資料庫

| 設定鍵 | 預設值 | 說明 |
|---|---|---|
| `server.port` | `8443` | API 監聽 port |
| `fido.persistence.mode` | `jpa` | **正式部署必須為 `jpa`**（接 SQL Server）。`memory` 為純記憶體、不建 DataSource，僅供本機開發，正式環境嚴禁使用。 |
| `spring.datasource.url` | `jdbc:sqlserver://REPLACE_WITH_SQLSERVER_HOST:1433;databaseName=FidoServerDb;encrypt=true;trustServerCertificate=false` | 佔位值，須替換為實際主機 |
| `spring.datasource.username` / `password` | `REPLACE_WITH_...` | 佔位值，建議走環境變數或密碼管理工具 |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 只驗證 entity 與既有 schema 是否一致，**不讓 Hibernate 自動建 / 改 schema**。schema 一律以 `002_create_tables.sql` 為權威來源。 |

### 4.2 Attestation 信任設定（安全關鍵）

| 設定鍵 | 預設值 | 說明 |
|---|---|---|
| `fido.attestation.mode` | `real` | **正式部署必須為 `real`**：對 attestation 簽章、Android Key Attestation 憑證鏈（內建 Google 官方 root 信任集合）、assertion 簽章做真實密碼學驗證。`stub` 僅供測試骨架，正式環境嚴禁使用。 |
| `fido.attestation.stub.*` | — | 僅在 `mode=stub` 時生效，正式環境不適用 |
| `fido.attestation.poc-trust.enabled` | `false` | **正式部署必須維持 `false`**。此為 PoC 專用：開啟時會額外信任指定路徑下的測試 root（供模擬器測試），開啟會削弱硬體真偽把關。 |
| `fido.attestation.poc-trust.extra-roots-location` | `file:./poc-trust-roots/*.pem` | 僅 `poc-trust.enabled=true` 時生效，指向 production classpath 之外 |

### 4.3 Session JWT 設定

| 設定鍵 | 預設值 | 說明 |
|---|---|---|
| `fido.session-jwt.issuer` | `https://fido.example.internal` | JWT `iss` claim。**須改為貴公司實際的 fido-server 識別值**，且貴公司後端驗證 JWT 時的 expected issuer 必須與此一致（見 API 串接手冊 §4）。 |
| `fido.session-jwt.ttl-seconds` | `120` | JWT 有效期（秒）。`exp = iat + 120`。 |
| `fido.session-jwt.kid` | `2026-fido-1` | JWKS 對應的 key id。金鑰輪替時新增而非直接覆蓋。 |

> **金鑰管理的 v1 限制**：目前 `fido-server` 在**每次程序啟動時於記憶體產生一組 EC P-256 金鑰對**，程序重啟即更換金鑰、先前簽出的 JWT 全部失效。由於 JWT 僅 120 秒有效且只用於一次性 session 交接，重啟造成的影響僅限於重啟當下極短時間內的登入。正式版若需持久化金鑰與平滑輪替，須由採用廠商自行實作金鑰持久化（見維護手冊第 4 節與技術限制手冊）。

### 4.4 其他

| 設定鍵 | 預設值 | 說明 |
|---|---|---|
| `fido.challenge.ttl-seconds` | `60` | WebAuthn challenge 時效（秒），對齊 API 合約 |
| `fido.rate-limit.default-tps` | `100` | 每租戶預設速率上限，可於 `tenants.rate_limit_tps` 逐租戶覆寫 |
| `fido.dev-seed.enabled` | `true` | **正式部署必須關閉（設 `false`）並移除整個 `fido.dev-seed` 區塊**。此為開發用種子租戶，會以固定的公開 API Key（`dev-api-key-...`）建立一個 `Demo Shop` 租戶，正式環境保留等於開了一個人人可用的後門。 |
| `management.endpoints.web.exposure.include` | `health,info` | 只暴露 health / info 兩個 actuator 端點 |
| `management.endpoint.health.show-details` | `never` | 健康檢查不對外洩漏細節 |
| `logging.level.com.fido.server` | `INFO` | 日誌等級 |

---

## 5. 部署方式

`fido-server` 是標準 Spring Boot 應用，產出可執行 jar：

1. **建置**：於 `fido-server/` 執行 `mvn clean package`（需 JDK 21），產出 `target/*.jar`。
2. **執行**：`java -jar fido-server-<version>.jar`，以外部設定覆寫敏感值，例如：
   ```
   java -jar fido-server.jar \
     --spring.datasource.url="jdbc:sqlserver://db-host:1433;databaseName=FidoServerDb;encrypt=true" \
     --spring.datasource.username="fido_app" \
     --spring.datasource.password="******" \
     --fido.dev-seed.enabled=false \
     --fido.session-jwt.issuer="https://fido.your-company.internal"
   ```
   （敏感值建議改用環境變數，如 `SPRING_DATASOURCE_PASSWORD`，避免出現在行程參數與歷史紀錄。）
3. **服務化（建議）**：以作業系統的服務管理器託管，確保開機自動啟動與異常重啟：
   - Linux：`systemd` service unit。
   - Windows：以 NSSM 之類的工具包裝成 Windows 服務，或搭配工作排程器。
4. **TLS**：如第 2.3 節所述，建議在前方以反向代理終結 TLS。

---

## 6. 初始租戶開通

`fido-server` v1 **沒有提供對外的租戶管理 REST 端點**（對齊 origin 綁定決策 OB6：租戶開通採人工 onboarding），改以**本機 admin CLI** 開通，由平台維運方（賣方）在伺服器主機上直接執行，不開任何網路端口。

### 6.1 `create-tenant` 指令

```
java -jar fido-server.jar --spring.profiles.active=admin-cli \
  --fido.admin.command=create-tenant \
  --fido.admin.tenant.name=<租戶顯示名稱> \
  --fido.admin.tenant.rp-id=<購物網站網域，如 shop.example.com> \
  --fido.admin.tenant.expected-origin=<允許的 Web origin，如 https://shop.example.com> \
  --fido.admin.tenant.rate-limit-tps=<選填，預設 100>
```

`rp_id` 唯一，重複開通會被明確擋下（不是資料庫例外堆疊）。指令會自動產生高熵 API Key，以下列規則落庫（對應 `ApiKeyService`，CLI 內部呼叫、不需人工手算）：

| `tenants` 欄位 | 內容 |
|---|---|
| `tenant_uid` | CLI 自動產生（UUID） |
| `name` / `rp_id` / `expected_origin` / `rate_limit_tps` | 取自上方參數 |
| `api_key_hash` | 該租戶 API Key 的 **SHA-256 雜湊（32 bytes 原始位元組）**，CLI 自動計算，系統只存雜湊、不存明文（見 db-schema.md DB2） |
| `api_key_prefix` | API Key 的前 12 個字元（明文，供運維識別），CLI 自動計算 |
| `status` | `ACTIVE` |

### 6.2 明碼 API Key 只印一次

指令執行成功後，明碼 API Key 只會印到終端機標準輸出一次，之後系統**無法再查回明碼**（只存雜湊）：

```
================================================================================
租戶開通成功
================================================================================
tenant_uid       : <uuid>
name             : <租戶名稱>
rp_id            : <rp_id>
expected_origin  : <expected_origin>
rate_limit_tps   : 100

API KEY（以下明碼只印一次，之後系統無法再查回）：
  fsk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
================================================================================
```

**請立即透過安全管道（加密通訊、密碼管理工具等）轉交給採用廠商，絕對不要把這組金鑰貼到 log 檔、issue tracker、聊天工具或版本控制系統。** 開通過程會寫入一筆 `audit_log`（`event_type=TENANT_PROVISIONED`），稽核紀錄只含 `tenant_uid`/API Key 前綴，不含明碼。

> `DevDataSeeder`（`fido.dev-seed.*`）僅供開發，種入公開字串 API Key 的示範租戶，**正式環境務必確認關閉**（`fido.dev-seed.enabled=false`），正式租戶一律走本機 CLI 開通，不再人工 `INSERT`。

### 6.3 原生 App 情境租戶的額外步驟（`add-app-binding`，opt-in）

若某租戶要啟用「購物網站原生 Android App 內直呼 Credential Manager」情境，須在 `create-tenant` 之後另外執行 `add-app-binding`（刻意獨立成兩步，呼應 opt-in 精神——大多數租戶只需純瀏覽器情境，不需此步驟）：

```
java -jar fido-server.jar --spring.profiles.active=admin-cli \
  --fido.admin.command=add-app-binding \
  --fido.admin.tenant.rp-id=<既有租戶的 rp_id> \
  --fido.admin.app.package-name=<Android applicationId> \
  --fido.admin.app.sha256-fingerprint=<App 簽章憑證 SHA-256 指紋> \
  --fido.admin.app.label=<選填，備註用途>
```

CLI 會自動換算 `apk_key_hash_origin`（`android:apk-key-hash:<base64url(指紋)>`）並寫入 `tenant_app_bindings`。完整申請流程（含廠商端 `assetlinks.json` 部署步驟）見 [`api-integration-guide.md`](api-integration-guide.md) 第 7 節。

### 6.4 金鑰輪替（`rotate-signing-key`）

同一支 admin CLI 也提供 session JWT 簽章金鑰的手動輪替指令，與租戶開通無關，用法與運維時機見 [`maintenance-guide.md`](maintenance-guide.md) 第 4 節。

---

## 7. 健康檢查與日誌

### 7.1 健康檢查端點

- `GET /actuator/health` — 存活 / 就緒檢查（公開端點，不需 API Key）。回 `UP` 代表服務正常。`show-details=never`，不對外洩漏元件細節。
- `GET /actuator/info` — 基本資訊端點。
- 建議監控系統定期輪詢 `/actuator/health`，並僅開放內網監控來源存取 `/actuator/*`。

### 7.2 JWKS 端點（部署後務必驗證可達）

- `GET /api/v1/.well-known/jwks.json` — 對外提供 session JWT 的 ES256 驗簽公鑰。貴公司購物網站後端會讀取此端點驗證 JWT 簽章，因此部署後務必確認它可被後端連到。

### 7.3 日誌

- `fido-server` 使用 Spring Boot 預設日誌（Logback），預設輸出至 stdout。以服務化方式部署時，請將 stdout / stderr 導向檔案或集中式日誌系統。
- 應用日誌等級由 `logging.level.com.fido.server` 控制（預設 `INFO`）。
- **稽核事件不寫在應用日誌**，而是落在資料庫 `audit_log` 表（保留 1 年），供客服 / 鑑識查詢。監控與稽核策略見維護手冊第 3、5 節。

---

## 8. 部署後檢查清單

- [ ] `fido.persistence.mode=jpa`，且 `spring.datasource.*` 指向正式 SQL Server
- [ ] `fido.attestation.mode=real`、`fido.attestation.poc-trust.enabled=false`
- [ ] `fido.dev-seed.enabled=false`，且已移除 dev-seed 區塊（不留 `dev-api-key-...` 後門）
- [ ] `fido.session-jwt.issuer` 已改為貴公司實際值，且與後端驗 JWT 的 expected issuer 一致
- [ ] TLS 已在反向代理 / 負載平衡器層終結，API 不走純 HTTP
- [ ] `infra/sql/001`–`006` 已依序執行，佔位值已替換
- [ ] TDE 憑證 `.cer` / `.pvk` 已異地備份，密碼分開保管
- [ ] 已手動觸發第一次完整備份，備份與清理 Agent Job 皆啟用
- [ ] `/actuator/health` 回 `UP`，`/api/v1/.well-known/jwks.json` 可被後端讀取
- [ ] 至少一個正式租戶已開通（`tenants` 有列，API Key 已安全交付）
