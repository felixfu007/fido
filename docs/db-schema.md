# FIDO 驗證伺服器 — 資料庫 Schema 定案（SQL Server）

- 版本：v1（草案，待人工複核）
- 最後更新：2026-07-21
- 目標平台：獨立 SQL Server 實例（與其他系統分開），TDE 全庫加密（見 CLAUDE.md）
- 對齊文件：`d:\fido\docs\api-contract.md`、`d:\fido\CLAUDE.md`
- 讀者：dev-engineer（JPA/實體對應）、devops-engineer（建庫、索引、備份、清理排程）

> 本文件是七張核心表的**權威 schema**。欄位命名與型別以此為準，dev / devops 請勿自行臆測。凡標記 **【本文件補充決策 DBn】** 者為 CLAUDE.md / API 合約未明確涵蓋、由本文件先行決定、待人工複核回填的細節，清單見文末附錄 B。
>
> 第七張表 `tenant_app_bindings`（原生 App 情境的 Digital Asset Links 授權登錄）為 origin 綁定架構定案（`docs/origin-binding.md` OB1/OB3）後新增，早期六張表的敘述已一併更新為七張。

---

## 目錄

1. [全域慣例與設計原則](#1-全域慣例與設計原則)
2. [ER 關係總覽](#2-er-關係總覽)
3. [`tenants`](#3-tenants)
4. [`fido_user_ref`](#4-fido_user_ref)
5. [`fido_credentials`](#5-fido_credentials)
6. [`bound_devices`](#6-bound_devices)
7. [`auth_challenges`](#7-auth_challenges)
8. [`audit_log`](#8-audit_log)
9. [`tenant_app_bindings`](#9-tenant_app_bindings)
10. [索引與外鍵總表](#10-索引與外鍵總表)
11. [資料保留與清理排程](#11-資料保留與清理排程)
12. [附錄 B：本文件補充決策清單](#附錄-b本文件補充決策清單)

---

## 1. 全域慣例與設計原則

- **【DB1】** 每張表都有 `created_at`；可變表另有 `updated_at`。型別 `DATETIME2(3)`，一律 UTC，預設 `SYSUTCDATETIME()`。理由：稽核可追溯、跨時區一致。
- **【DB3】** 內部主鍵用 `BIGINT IDENTITY`（緊湊、索引友善、供 FK）；對外公開識別另用不透明值（`tenant_uid`/`device_id` 用 `UNIQUEIDENTIFIER`、`ceremony_id` 用前綴字串），避免以連續序號對外洩漏規模與便於列舉。API 合約中的 `deviceId`、`ceremonyId` 對應這些公開欄位而非內部 PK。
- **【DB5】** 所有文字欄位用 `NVARCHAR`（Unicode），支援中文 `device_name` / `display_name`。
- **【DB6】** 所有二進位資料（challenge、credential id、public key、user_handle 等）以 `VARBINARY` 存 **raw bytes**；API 層負責 base64url ↔ bytes 轉換，資料庫不存 base64 字串。
- **【DB7】** 子表反正規化 `tenant_id`，讓每一筆查詢都能以 `tenant_id` 做租戶隔離與複合索引，避免跨表 join 才能過濾租戶。
- **【DB8】** 時間全部 UTC 儲存。
- **【DB10】** 撤銷/刪除採軟刪除：`status = 'REVOKED'` + `revoked_at` + `revoked_reason`，不實體刪列（對齊 API 合約 D10、稽核保留 1 年）。
- 外鍵一律 `ON DELETE NO ACTION`（不做實體 cascade，因採軟刪除）。
- 字串列舉值以 `CHECK` 約束固定，並在應用層以 enum 對應。
- Schema 名稱：使用 `dbo`（單一資料庫、單一 schema，多租戶以 `tenant_id` 邏輯隔離，非每租戶一 schema）。**【DB15】**

---

## 2. ER 關係總覽

```
tenants (1) ──< fido_user_ref (1) ──< fido_credentials (1) ──(1:1)── bound_devices
   │                   │                      │
   │                   │                      └──< (被 auth_challenges 於驗證時參照)
   ├──< auth_challenges (challenge 綁定租戶，登入前可能尚未綁定 user_ref)
   ├──< audit_log (所有事件，含 pre-auth 失敗，tenant/user 可為 NULL)
   └──< tenant_app_bindings (原生 App 情境：租戶授權的 Android App 簽章指紋)
```

- `tenants 1 : N fido_user_ref`：一個購物網站租戶下多個使用者參照。
- `tenants 1 : N tenant_app_bindings`：一個租戶可授權多支原生 App（正式/測試簽章、多 App）代表其網域發起 WebAuthn（見 `docs/origin-binding.md` 第 5 節）。僅原生 App 情境（opt-in）的租戶會有此列；純瀏覽器租戶此表無列。
- `fido_user_ref 1 : N fido_credentials`：一使用者可註冊多台裝置 → 多把憑證（對齊「多裝置」決策）。
- `fido_credentials 1 : 1 bound_devices` **【DB14】**：v1 情境 A 每次註冊在一台裝置產生一把 platform 憑證，故一憑證對一裝置。日後若同裝置多憑證再改 1:N。
- `auth_challenges`：短生命週期，綁租戶；註冊時綁 `user_ref`，登入（usernameless）時 `user_ref_id` 可為 NULL，於驗證成功後由 credential 反查。
- `audit_log`：獨立事件表，`tenant_id`/`user_ref_id` 允許 NULL（涵蓋 API Key 無效等 pre-auth 事件）。

---

## 3. `tenants`

購物網站租戶。持有 RP ID、預期 origin、API Key（雜湊儲存）、狀態與速率上限。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `tenant_id` | BIGINT IDENTITY | 否 | | 內部 PK |
| `tenant_uid` | UNIQUEIDENTIFIER | 否 | NEWID() | 對外租戶識別，= JWT `tid` claim |
| `name` | NVARCHAR(200) | 否 | | 租戶顯示名 |
| `rp_id` | NVARCHAR(255) | 否 | | WebAuthn RP ID（購物網站網域），唯一 |
| `expected_origin` | NVARCHAR(512) | 否 | | 允許的 WebAuthn origin（可為 JSON 陣列字串，支援多個 `https://` origin） |
| `api_key_hash` | VARBINARY(32) | 否 | | API Key 的 SHA-256 雜湊，唯一 **【DB2】** |
| `api_key_prefix` | NVARCHAR(12) | 否 | | API Key 前綴（明文），供查找與運維識別 |
| `status` | NVARCHAR(20) | 否 | 'ACTIVE' | CHECK IN ('ACTIVE','DISABLED') |
| `rate_limit_tps` | INT | 否 | 100 | 每租戶 TPS 上限（對齊 CLAUDE.md 100 TPS） |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `updated_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |

**鍵/索引**：PK `tenant_id`；UNIQUE `tenant_uid`；UNIQUE `rp_id`；UNIQUE `api_key_hash`；INDEX `api_key_prefix`。

**API 對應**：`X-API-Key` 驗證（比對 `api_key_hash`）、RP ID 綁定、租戶速率限制、JWT `aud`(=`rp_id`)/`tid`(=`tenant_uid`)、`403 TENANT_DISABLED`(`status`)。

**【DB2】** API Key 只存 SHA-256 雜湊 + 明文前綴，不存明文；查驗時對整把 key 取雜湊比對。理由：即使 DB 外洩也無法還原 API Key。

```sql
CREATE TABLE dbo.tenants (
    tenant_id       BIGINT IDENTITY(1,1) NOT NULL,
    tenant_uid      UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_tenants_uid DEFAULT NEWID(),
    name            NVARCHAR(200) NOT NULL,
    rp_id           NVARCHAR(255) NOT NULL,
    expected_origin NVARCHAR(512) NOT NULL,
    api_key_hash    VARBINARY(32) NOT NULL,
    api_key_prefix  NVARCHAR(12)  NOT NULL,
    status          NVARCHAR(20)  NOT NULL CONSTRAINT DF_tenants_status DEFAULT 'ACTIVE',
    rate_limit_tps  INT           NOT NULL CONSTRAINT DF_tenants_rate DEFAULT 100,
    created_at      DATETIME2(3)  NOT NULL CONSTRAINT DF_tenants_created DEFAULT SYSUTCDATETIME(),
    updated_at      DATETIME2(3)  NOT NULL CONSTRAINT DF_tenants_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_tenants PRIMARY KEY (tenant_id),
    CONSTRAINT UQ_tenants_uid UNIQUE (tenant_uid),
    CONSTRAINT UQ_tenants_rpid UNIQUE (rp_id),
    CONSTRAINT UQ_tenants_apikey UNIQUE (api_key_hash),
    CONSTRAINT CK_tenants_status CHECK (status IN ('ACTIVE','DISABLED'))
);
CREATE INDEX IX_tenants_apikey_prefix ON dbo.tenants (api_key_prefix);
```

---

## 4. `fido_user_ref`

購物網站使用者在 FIDO 伺服器的**參照**（非身分來源）。持有 WebAuthn `user_handle`。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `user_ref_id` | BIGINT IDENTITY | 否 | | 內部 PK |
| `tenant_id` | BIGINT | 否 | | FK → tenants |
| `external_user_id` | NVARCHAR(255) | 否 | | 購物網站的使用者 ID（API 的 `externalUserId`） |
| `user_handle` | VARBINARY(64) | 否 | | WebAuthn `user.id`，隨機 32 bytes；對外以 base64url 呈現 |
| `display_name` | NVARCHAR(255) | 是 | | WebAuthn `user.displayName` |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `updated_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |

**鍵/索引**：PK `user_ref_id`；UNIQUE (`tenant_id`,`external_user_id`)；UNIQUE (`tenant_id`,`user_handle`)。

**API 對應**：`registration/options`(upsert)、`authentication/options`、裝置列表/綁定狀態的 user 定位。`enrolled=false` 即此表無列或無 active 憑證。

```sql
CREATE TABLE dbo.fido_user_ref (
    user_ref_id      BIGINT IDENTITY(1,1) NOT NULL,
    tenant_id        BIGINT NOT NULL,
    external_user_id NVARCHAR(255) NOT NULL,
    user_handle      VARBINARY(64) NOT NULL,
    display_name     NVARCHAR(255) NULL,
    created_at       DATETIME2(3) NOT NULL CONSTRAINT DF_userref_created DEFAULT SYSUTCDATETIME(),
    updated_at       DATETIME2(3) NOT NULL CONSTRAINT DF_userref_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_fido_user_ref PRIMARY KEY (user_ref_id),
    CONSTRAINT FK_userref_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT UQ_userref_extid UNIQUE (tenant_id, external_user_id),
    CONSTRAINT UQ_userref_handle UNIQUE (tenant_id, user_handle)
);
```

---

## 5. `fido_credentials`

WebAuthn 憑證（公鑰）。對齊 API 合約欄位：credential_id、public_key、sign_count、aaguid、transports、status。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `credential_pk` | BIGINT IDENTITY | 否 | | 內部 PK |
| `user_ref_id` | BIGINT | 否 | | FK → fido_user_ref |
| `tenant_id` | BIGINT | 否 | | FK → tenants（反正規化） |
| `credential_id` | VARBINARY(1024) | 否 | | raw credential id |
| `credential_id_sha256` | VARBINARY(32) | 否 | | credential_id 的 SHA-256，供唯一/查找索引 **【DB4】** |
| `public_key` | VARBINARY(512) | 否 | | COSE_Key 格式公鑰 |
| `cose_alg` | INT | 否 | | COSE 演算法 ID（-7 ES256 / -257 RS256） |
| `sign_count` | BIGINT | 否 | 0 | 最近一次 authenticator sign counter **【DB9】** |
| `aaguid` | BINARY(16) | 是 | | Authenticator AAGUID |
| `transports` | NVARCHAR(100) | 是 | | JSON 陣列字串，如 `["internal"]` |
| `attestation_format` | NVARCHAR(50) | 是 | | 如 `android-key` |
| `status` | NVARCHAR(20) | 否 | 'ACTIVE' | CHECK IN ('ACTIVE','REVOKED') |
| `revoked_at` | DATETIME2(3) | 是 | | 撤銷時間 |
| `revoked_reason` | NVARCHAR(50) | 是 | | CHECK IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED') |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `updated_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `last_used_at` | DATETIME2(3) | 是 | | 最近一次成功 assertion |

**鍵/索引**：PK `credential_pk`；UNIQUE (`tenant_id`,`credential_id_sha256`)；INDEX (`user_ref_id`,`status`)；INDEX (`tenant_id`,`status`)。

**【DB4】** 因 SQL Server 索引鍵上限 900 bytes，而 raw `credential_id` 可能較長，故以 `credential_id_sha256` 建唯一索引與查找鍵；`credential_id` 原值仍完整保存供比對回傳。

**【DB9】** `sign_count` 用 BIGINT（WebAuthn counter 為 uint32，BIGINT 可完整容納並預留）。sign counter 倒退（`new <= stored` 且非 0/0）時，應用層將本列與對應 `bound_devices` 設為 `REVOKED`、`revoked_reason='COUNTER_REGRESSION'`（對齊 API 合約 3.2）。

**API 對應**：`registration/result`(insert)、`authentication/result`(讀公鑰驗簽、更新 sign_count/last_used_at、倒退時撤銷)、裝置列表(credentialId/aaguid/status)、綁定狀態(active 計數)。

```sql
CREATE TABLE dbo.fido_credentials (
    credential_pk        BIGINT IDENTITY(1,1) NOT NULL,
    user_ref_id          BIGINT NOT NULL,
    tenant_id            BIGINT NOT NULL,
    credential_id        VARBINARY(1024) NOT NULL,
    credential_id_sha256 VARBINARY(32) NOT NULL,
    public_key           VARBINARY(512) NOT NULL,
    cose_alg             INT NOT NULL,
    sign_count           BIGINT NOT NULL CONSTRAINT DF_cred_signcount DEFAULT 0,
    aaguid               BINARY(16) NULL,
    transports           NVARCHAR(100) NULL,
    attestation_format   NVARCHAR(50) NULL,
    status               NVARCHAR(20) NOT NULL CONSTRAINT DF_cred_status DEFAULT 'ACTIVE',
    revoked_at           DATETIME2(3) NULL,
    revoked_reason       NVARCHAR(50) NULL,
    created_at           DATETIME2(3) NOT NULL CONSTRAINT DF_cred_created DEFAULT SYSUTCDATETIME(),
    updated_at           DATETIME2(3) NOT NULL CONSTRAINT DF_cred_updated DEFAULT SYSUTCDATETIME(),
    last_used_at         DATETIME2(3) NULL,
    CONSTRAINT PK_fido_credentials PRIMARY KEY (credential_pk),
    CONSTRAINT FK_cred_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
    CONSTRAINT FK_cred_tenant  FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT UQ_cred_idhash  UNIQUE (tenant_id, credential_id_sha256),
    CONSTRAINT CK_cred_status  CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT CK_cred_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
);
CREATE INDEX IX_cred_userref_status ON dbo.fido_credentials (user_ref_id, status);
CREATE INDEX IX_cred_tenant_status  ON dbo.fido_credentials (tenant_id, status);
```

---

## 6. `bound_devices`

註冊憑證所在的實體裝置與其硬體安全屬性。對齊 API 合約：device_name、model、os_version、security_level、attestation 摘要。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `device_pk` | BIGINT IDENTITY | 否 | | 內部 PK |
| `device_id` | UNIQUEIDENTIFIER | 否 | NEWID() | 對外裝置識別（API `deviceId`，前端顯示前綴 `dev_` 由 API 層加） |
| `credential_pk` | BIGINT | 否 | | FK → fido_credentials，1:1（唯一） |
| `user_ref_id` | BIGINT | 否 | | FK → fido_user_ref（反正規化） |
| `tenant_id` | BIGINT | 否 | | FK → tenants（反正規化） |
| `device_name` | NVARCHAR(100) | 是 | | 使用者自訂名稱 |
| `model` | NVARCHAR(100) | 是 | | 裝置型號（如 Pixel 8） |
| `os_version` | NVARCHAR(50) | 是 | | 如 Android 14 |
| `security_level` | NVARCHAR(20) | 否 | | CHECK IN ('TEE','STRONG_BOX') **【DB13】** |
| `attestation_summary` | NVARCHAR(1000) | 是 | | Key Attestation 驗證摘要（JSON：憑證鏈根、序號、securityLevel、verifiedBootState 等） |
| `status` | NVARCHAR(20) | 否 | 'ACTIVE' | CHECK IN ('ACTIVE','REVOKED') |
| `revoked_at` | DATETIME2(3) | 是 | | |
| `revoked_reason` | NVARCHAR(50) | 是 | | CHECK IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED') |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `updated_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `last_used_at` | DATETIME2(3) | 是 | | |

**鍵/索引**：PK `device_pk`；UNIQUE `device_id`；UNIQUE `credential_pk`(1:1)；INDEX (`user_ref_id`,`status`)；INDEX (`tenant_id`,`status`)。

**【DB13】** `security_level` 僅允許 `'TEE'`/`'STRONG_BOX'`（對應 Android `TRUSTED_ENVIRONMENT`/`STRONGBOX`）。`SOFTWARE` 等級在註冊階段即被 API 以 `422 HARDWARE_SECURITY_NOT_MET` 拒絕，不會落庫（對齊 CLAUDE.md 強制硬體安全區）。

**API 對應**：`registration/result`(insert)、裝置列表(全欄位)、`DELETE .../devices/{deviceId}`(以 `device_id` 定位，軟撤銷)、`authentication/result`(更新 last_used_at / 倒退時撤銷)。

```sql
CREATE TABLE dbo.bound_devices (
    device_pk           BIGINT IDENTITY(1,1) NOT NULL,
    device_id           UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_dev_id DEFAULT NEWID(),
    credential_pk       BIGINT NOT NULL,
    user_ref_id         BIGINT NOT NULL,
    tenant_id           BIGINT NOT NULL,
    device_name         NVARCHAR(100) NULL,
    model               NVARCHAR(100) NULL,
    os_version          NVARCHAR(50) NULL,
    security_level      NVARCHAR(20) NOT NULL,
    attestation_summary NVARCHAR(1000) NULL,
    status              NVARCHAR(20) NOT NULL CONSTRAINT DF_dev_status DEFAULT 'ACTIVE',
    revoked_at          DATETIME2(3) NULL,
    revoked_reason      NVARCHAR(50) NULL,
    created_at          DATETIME2(3) NOT NULL CONSTRAINT DF_dev_created DEFAULT SYSUTCDATETIME(),
    updated_at          DATETIME2(3) NOT NULL CONSTRAINT DF_dev_updated DEFAULT SYSUTCDATETIME(),
    last_used_at        DATETIME2(3) NULL,
    CONSTRAINT PK_bound_devices PRIMARY KEY (device_pk),
    CONSTRAINT UQ_dev_id UNIQUE (device_id),
    CONSTRAINT UQ_dev_cred UNIQUE (credential_pk),
    CONSTRAINT FK_dev_cred FOREIGN KEY (credential_pk) REFERENCES dbo.fido_credentials(credential_pk),
    CONSTRAINT FK_dev_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
    CONSTRAINT FK_dev_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT CK_dev_seclevel CHECK (security_level IN ('TEE','STRONG_BOX')),
    CONSTRAINT CK_dev_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT CK_dev_revreason CHECK (revoked_reason IN ('USER_REQUEST','COUNTER_REGRESSION','ADMIN','TENANT_DISABLED'))
);
CREATE INDEX IX_dev_userref_status ON dbo.bound_devices (user_ref_id, status);
CREATE INDEX IX_dev_tenant_status  ON dbo.bound_devices (tenant_id, status);
```

---

## 7. `auth_challenges`

註冊 / 登入 ceremony 的 challenge，短生命週期（60 秒）。對應 API 的 `ceremonyId`。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `challenge_pk` | BIGINT IDENTITY | 否 | | 內部 PK |
| `ceremony_id` | NVARCHAR(64) | 否 | | 對外不透明 ID（前綴 `reg_`/`auth_`），唯一 |
| `tenant_id` | BIGINT | 否 | | FK → tenants |
| `user_ref_id` | BIGINT | 是 | | FK → fido_user_ref；usernameless 登入時可 NULL |
| `challenge` | VARBINARY(64) | 否 | | 隨機 32 bytes |
| `ceremony_type` | NVARCHAR(20) | 否 | | CHECK IN ('REGISTRATION','AUTHENTICATION') |
| `status` | NVARCHAR(20) | 否 | 'PENDING' | CHECK IN ('PENDING','CONSUMED','EXPIRED') |
| `expires_at` | DATETIME2(3) | 否 | | = created_at + 60 秒（伺服器端權威時效） |
| `consumed_at` | DATETIME2(3) | 是 | | |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |

**鍵/索引**：PK `challenge_pk`；UNIQUE `ceremony_id`；INDEX `expires_at`（清理用）；INDEX (`tenant_id`,`status`)。

**時效落地**：API 回傳 `timeout:60000` 只是前端提示；`result` 端點以 `expires_at` 與 `status` 為權威判斷（過期或非 PENDING → `400 CHALLENGE_EXPIRED`）。成功驗證後將 `status='CONSUMED'`、寫 `consumed_at`，確保一次性、防重放。

**API 對應**：`registration/options`、`authentication/options`(insert PENDING)；`*/result`(讀→驗→CONSUMED)。

```sql
CREATE TABLE dbo.auth_challenges (
    challenge_pk  BIGINT IDENTITY(1,1) NOT NULL,
    ceremony_id   NVARCHAR(64) NOT NULL,
    tenant_id     BIGINT NOT NULL,
    user_ref_id   BIGINT NULL,
    challenge     VARBINARY(64) NOT NULL,
    ceremony_type NVARCHAR(20) NOT NULL,
    status        NVARCHAR(20) NOT NULL CONSTRAINT DF_chal_status DEFAULT 'PENDING',
    expires_at    DATETIME2(3) NOT NULL,
    consumed_at   DATETIME2(3) NULL,
    created_at    DATETIME2(3) NOT NULL CONSTRAINT DF_chal_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_auth_challenges PRIMARY KEY (challenge_pk),
    CONSTRAINT UQ_chal_ceremony UNIQUE (ceremony_id),
    CONSTRAINT FK_chal_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT FK_chal_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
    CONSTRAINT CK_chal_type CHECK (ceremony_type IN ('REGISTRATION','AUTHENTICATION')),
    CONSTRAINT CK_chal_status CHECK (status IN ('PENDING','CONSUMED','EXPIRED'))
);
CREATE INDEX IX_chal_expires ON dbo.auth_challenges (expires_at);
CREATE INDEX IX_chal_tenant_status ON dbo.auth_challenges (tenant_id, status);
```

---

## 8. `audit_log`

稽核事件（保留 1 年）。涵蓋註冊、登入、撤銷、自動撤銷、pre-auth 失敗等。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `audit_id` | BIGINT IDENTITY | 否 | | 內部 PK |
| `tenant_id` | BIGINT | 是 | | FK → tenants；pre-auth（API Key 無效）可 NULL |
| `user_ref_id` | BIGINT | 是 | | FK → fido_user_ref；可 NULL |
| `device_pk` | BIGINT | 是 | | 關聯裝置（不設硬 FK，容忍歷史裝置）**【DB16】** |
| `event_type` | NVARCHAR(50) | 否 | | 如 REG_SUCCESS/AUTH_SUCCESS/AUTO_REVOKE_COUNTER_REGRESSION/DEVICE_REVOKED_BY_USER/DEVICE_REVOKE_NOOP/AUTH_FAIL |
| `outcome` | NVARCHAR(20) | 否 | | CHECK IN ('SUCCESS','FAILURE') |
| `request_id` | NVARCHAR(64) | 是 | | 對應 `X-Request-Id` / 錯誤回應 `traceId` |
| `ip_address` | NVARCHAR(45) | 是 | | 呼叫端 IP（IPv4/IPv6） |
| `detail` | NVARCHAR(MAX) | 是 | | JSON 補充（如偵測到的 security_level、matched 旗標等） |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | 事件時間 |

**鍵/索引**：PK `audit_id`；INDEX (`tenant_id`,`user_ref_id`,`created_at`)；INDEX (`created_at`)（清理與時間範圍查詢）；INDEX (`tenant_id`,`event_type`,`created_at`)。

**【DB16】** `device_pk` 不設硬 FK，避免軟刪除/資料清理時稽核列連動受限；以應用層保證一致性。

**API 對應**：所有端點寫入；`GET .../audit-events`(5.2) 唯讀查詢，永遠以 `tenant_id` 過濾僅回本租戶資料。

```sql
CREATE TABLE dbo.audit_log (
    audit_id     BIGINT IDENTITY(1,1) NOT NULL,
    tenant_id    BIGINT NULL,
    user_ref_id  BIGINT NULL,
    device_pk    BIGINT NULL,
    event_type   NVARCHAR(50) NOT NULL,
    outcome      NVARCHAR(20) NOT NULL,
    request_id   NVARCHAR(64) NULL,
    ip_address   NVARCHAR(45) NULL,
    detail       NVARCHAR(MAX) NULL,
    created_at   DATETIME2(3) NOT NULL CONSTRAINT DF_audit_created DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT FK_audit_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT FK_audit_userref FOREIGN KEY (user_ref_id) REFERENCES dbo.fido_user_ref(user_ref_id),
    CONSTRAINT CK_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE'))
);
CREATE INDEX IX_audit_tenant_user_time ON dbo.audit_log (tenant_id, user_ref_id, created_at);
CREATE INDEX IX_audit_time ON dbo.audit_log (created_at);
CREATE INDEX IX_audit_tenant_type_time ON dbo.audit_log (tenant_id, event_type, created_at);
```

---

## 9. `tenant_app_bindings`

原生 App 情境（`docs/origin-binding.md` OB1）下，租戶授權「代表其網域發起 WebAuthn」的 Android App 簽章指紋登錄。**僅 opt-in 啟用原生 App 登入的租戶會有此表資料；純瀏覽器情境不需要任何列。** 此表是伺服器把 `android:apk-key-hash:...` app origin 納入該租戶 origin 允許清單的**權威來源與管理/稽核依據**。

> App 簽章指紋是**租戶層**屬性（同一支 App、全租戶使用者共用同一簽章），與「使用者持有哪台裝置」正交，故置於本表而非 `bound_devices`（見 `docs/origin-binding.md` 第 5.4 節）。

| 欄位 | 型別 | Null | 預設 | 說明 |
|---|---|---|---|---|
| `app_binding_pk` | BIGINT IDENTITY | 否 | | 內部 PK |
| `binding_uid` | UNIQUEIDENTIFIER | 否 | NEWID() | 對外不透明識別；**【DB17】** 供日後租戶管理 API 使用，v1 尚未經 API 暴露（對齊 origin-binding.md OB6 人工 onboarding） |
| `tenant_id` | BIGINT | 否 | | FK → tenants |
| `package_name` | NVARCHAR(255) | 否 | | Android App 的 applicationId，如 `com.shop.example` |
| `sha256_cert_fingerprint` | VARBINARY(32) | 否 | | App 簽章憑證 DER 的 SHA-256 原始位元組（對齊 DB6：二進位存 raw）。即 `assetlinks.json` 內 `sha256_cert_fingerprints` 的同一份雜湊 |
| `apk_key_hash_origin` | NVARCHAR(120) | 否 | | 由上者換算的 WebAuthn app origin，`android:apk-key-hash:<base64url(fingerprint)>`；**伺服器 origin 允許清單即比對此值**，冗餘保存以利直接比對與管理顯示 |
| `label` | NVARCHAR(100) | 是 | | 人類可讀標籤，如「正式版 App」「測試簽章」 |
| `status` | NVARCHAR(20) | 否 | 'ACTIVE' | CHECK IN ('ACTIVE','REVOKED')，軟刪除（對齊 DB10），支援 App 簽章輪替不實體刪列 |
| `revoked_at` | DATETIME2(3) | 是 | | 撤銷時間 |
| `revoked_reason` | NVARCHAR(50) | 是 | | CHECK IN ('ADMIN','KEY_ROTATION','SECURITY') |
| `created_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |
| `updated_at` | DATETIME2(3) | 否 | SYSUTCDATETIME() | |

**鍵/索引**：PK `app_binding_pk`；UNIQUE `binding_uid`；UNIQUE (`tenant_id`,`package_name`,`sha256_cert_fingerprint`)（同一租戶同 App 同指紋不重複登錄）；INDEX (`tenant_id`,`status`)（列出租戶 active 授權）。

**【DB17】** 新增本表（origin-binding.md OB3 選項 B）而非在 `tenants` 加 JSON 欄位（OB3 選項 A，已評估未採用）。理由：可逐筆稽核 App 授權的增刪與輪替、支援一租戶多 App、指紋可建唯一索引防重複；代價是核心表由六張增為七張，已回填 CLAUDE.md 與本文件。伺服器載入租戶時，可將本表 active 列的 `apk_key_hash_origin` 併入 `tenants.expected_origin` 允許清單一起比對（`expected_origin` 仍保留 web origin；app origin 以本表為權威來源）。

**API / 服務對應**：無 v1 REST 端點（origin-binding.md OB6：登錄由人工 onboarding，非自助 API）；`authentication/result` / `registration/result` 驗證 origin 時，若 `clientDataJSON.origin` 為 app origin，比對本表 active 列的 `apk_key_hash_origin`。管理介面/客服可讀本表顯示租戶已授權的 App。

```sql
CREATE TABLE dbo.tenant_app_bindings (
    app_binding_pk          BIGINT IDENTITY(1,1) NOT NULL,
    binding_uid             UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_appbind_uid DEFAULT NEWID(),
    tenant_id               BIGINT NOT NULL,
    package_name            NVARCHAR(255) NOT NULL,
    sha256_cert_fingerprint VARBINARY(32) NOT NULL,
    apk_key_hash_origin     NVARCHAR(120) NOT NULL,
    label                   NVARCHAR(100) NULL,
    status                  NVARCHAR(20) NOT NULL CONSTRAINT DF_appbind_status DEFAULT 'ACTIVE',
    revoked_at              DATETIME2(3) NULL,
    revoked_reason          NVARCHAR(50) NULL,
    created_at              DATETIME2(3) NOT NULL CONSTRAINT DF_appbind_created DEFAULT SYSUTCDATETIME(),
    updated_at              DATETIME2(3) NOT NULL CONSTRAINT DF_appbind_updated DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_tenant_app_bindings PRIMARY KEY (app_binding_pk),
    CONSTRAINT UQ_appbind_uid UNIQUE (binding_uid),
    CONSTRAINT UQ_appbind_tenant_pkg_fp UNIQUE (tenant_id, package_name, sha256_cert_fingerprint),
    CONSTRAINT FK_appbind_tenant FOREIGN KEY (tenant_id) REFERENCES dbo.tenants(tenant_id),
    CONSTRAINT CK_appbind_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT CK_appbind_revreason CHECK (revoked_reason IN ('ADMIN','KEY_ROTATION','SECURITY'))
);
CREATE INDEX IX_appbind_tenant_status ON dbo.tenant_app_bindings (tenant_id, status);
```

---

## 10. 索引與外鍵總表

| 表 | PK | UNIQUE | 其他索引 | FK |
|---|---|---|---|---|
| tenants | tenant_id | tenant_uid, rp_id, api_key_hash | api_key_prefix | — |
| fido_user_ref | user_ref_id | (tenant_id,external_user_id), (tenant_id,user_handle) | — | tenant_id→tenants |
| fido_credentials | credential_pk | (tenant_id,credential_id_sha256) | (user_ref_id,status), (tenant_id,status) | user_ref_id→fido_user_ref, tenant_id→tenants |
| bound_devices | device_pk | device_id, credential_pk | (user_ref_id,status), (tenant_id,status) | credential_pk→fido_credentials, user_ref_id→fido_user_ref, tenant_id→tenants |
| auth_challenges | challenge_pk | ceremony_id | expires_at, (tenant_id,status) | tenant_id→tenants, user_ref_id→fido_user_ref |
| audit_log | audit_id | — | (tenant_id,user_ref_id,created_at), created_at, (tenant_id,event_type,created_at) | tenant_id→tenants, user_ref_id→fido_user_ref |
| tenant_app_bindings | app_binding_pk | binding_uid, (tenant_id,package_name,sha256_cert_fingerprint) | (tenant_id,status) | tenant_id→tenants |

---

## 11. 資料保留與清理排程

- **【DB11】** `auth_challenges`：challenge 為一次性短生命週期。建議 SQL Agent Job 每分鐘將 `expires_at < now` 的 PENDING 標為 EXPIRED，並每日刪除 `created_at < now-1天` 的列（保留少量供近期除錯即可，非稽核來源）。
- **【DB12】** `audit_log`：保留 1 年（對齊 CLAUDE.md）。建議按月做資料分割（partition by `created_at` 月份）或 SQL Agent Job 每日刪除 `created_at < now-365天`。若採 partition，可用 SWITCH + DROP 高效清理。
- `tenant_app_bindings`：**組態性資料，無自動清理**。與 `tenants` 同生命週期，撤銷採軟刪除（`status='REVOKED'`）長期保留供稽核；僅隨租戶整體下線時一併人工處理。
- TDE 全庫加密與定期備份由 devops-engineer 依 CLAUDE.md 設定，不在本 schema 文件內展開。

---

## 附錄 B：本文件補充決策清單

| 編號 | 決策 | 理由 |
|---|---|---|
| DB1 | 每表加 `created_at`（可變表加 `updated_at`），`DATETIME2(3)` UTC | 稽核可追溯、跨時區一致 |
| DB2 | API Key 以 SHA-256 雜湊 + 明文前綴儲存，不存明文 | DB 外洩也無法還原 Key |
| DB3 | 內部 PK 用 BIGINT IDENTITY；對外識別另用 UNIQUEIDENTIFIER / 前綴字串 | 緊湊索引 + 防序號列舉 |
| DB4 | `credential_id` 存 VARBINARY(1024) + `credential_id_sha256` 唯一索引 | 繞過 SQL Server 900-byte 索引鍵上限 |
| DB5 | 文字欄位一律 NVARCHAR | 支援中文等 Unicode |
| DB6 | 二進位存 raw VARBINARY，base64url 由 API 層轉換 | 儲存效率、避免編碼混淆 |
| DB7 | 子表反正規化 `tenant_id` | 多租戶隔離查詢與複合索引 |
| DB8 | 時間全 UTC 儲存 | 一致性 |
| DB9 | `sign_count` 用 BIGINT | 完整容納 uint32 並預留 |
| DB10 | 撤銷採 status+revoked_at/reason 軟刪除 | 對齊 API D10、稽核保留 |
| DB11 | `auth_challenges` 過期標記+每日清理排程 | 短生命週期、非稽核來源 |
| DB12 | `audit_log` 保留 1 年，按月分割/排程清理 | 對齊 CLAUDE.md 稽核 1 年 |
| DB13 | `security_level` 僅 'TEE'/'STRONG_BOX'，SOFTWARE 於註冊即拒 | 對齊強制硬體安全區 |
| DB14 | `fido_credentials` : `bound_devices` = 1:1（v1） | 情境 A 每次註冊一裝置一憑證；日後可放寬 |
| DB15 | 單一資料庫單一 `dbo` schema，多租戶以 `tenant_id` 邏輯隔離 | 中小規模、運維簡單；非每租戶一 schema |
| DB16 | `audit_log.device_pk` 不設硬 FK | 避免清理/軟刪動作被稽核列連動限制 |
| DB17 | 新增第七張表 `tenant_app_bindings` 存租戶授權 App 簽章指紋（origin-binding.md OB3 選項 B，已拍板；未採選項 A 的 `tenants` JSON 欄位） | 可逐筆稽核/輪替 App 授權、支援一租戶多 App、指紋建唯一索引防重複；已回填 CLAUDE.md 六張→七張 |

> 待複核項：DB2（API Key 雜湊儲存）、DB14（1:1 憑證裝置關係）、DB15（單 schema 邏輯隔離）屬會影響實作與運維的取捨，建議優先確認。DB17 已由人類拍板（OB3 選項 B）。複核通過後交接 dev-engineer（JPA 實體，含新 `tenant_app_bindings` 實體）與 devops-engineer（建庫腳本、索引、清理 Job、TDE/備份，並重新在 LocalDB 驗證含第七張表的 schema 建置）。
