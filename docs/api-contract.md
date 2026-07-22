# FIDO 驗證伺服器 — REST API 合約

- 版本：v1（草案，待人工複核）
- 最後更新：2026-07-21
- 適用架構情境：A（標準 WebAuthn，同裝置）
- 對應共識文件：`d:\fido\CLAUDE.md`

> 本文件是 **FIDO 驗證伺服器對外的後端 REST API 合約**。所有端點的呼叫方（除 JWKS 與 health 外）都是 **購物網站的後端**（租戶 backend），以 server-to-server 方式呼叫，並以 API Key 表明租戶身分。FIDO 驗證 APP（Android CredentialProviderService）不直接呼叫本 API；它透過同裝置的 WebAuthn / Credential Manager 與購物網站前端互動，產生的 attestation / assertion 由購物網站前端交回購物網站後端，再由後端轉呼叫本 API 驗證。
>
> 凡標記 **【本文件補充決策】** 者，為 CLAUDE.md 未覆蓋、由系統分析師於本文件先行決定的細節，需人工複核後回填 CLAUDE.md。全部清單見文末附錄 A。

---

## 目錄

1. [API 總覽與慣例](#1-api-總覽與慣例)
   - 1.1 Base path 與版本策略
   - 1.2 租戶識別與認證（API Key）
   - 1.3 短時效自簽 JWT（session 交接）
   - 1.4 通用錯誤格式與錯誤碼
   - 1.5 資料編碼慣例
2. [註冊流程 API（WebAuthn Attestation Ceremony）](#2-註冊流程-apiwebauthn-attestation-ceremony)
   - 2.1 產生註冊 challenge
   - 2.2 提交註冊結果
3. [登入流程 API（WebAuthn Assertion Ceremony）](#3-登入流程-apiwebauthn-assertion-ceremony)
   - 3.1 產生登入 challenge
   - 3.2 提交登入結果（含 sign counter 與自動撤銷、JWT 簽發）
   - 3.3 JWKS 公鑰端點
4. [裝置管理 API](#4-裝置管理-api)
   - 4.1 列出使用者已註冊裝置
   - 4.2 撤銷 / 刪除裝置
5. [查詢類 API](#5-查詢類-api)
   - 5.1 查詢使用者 FIDO 綁定狀態
   - 5.2 稽核事件查詢（客服用，選配）
6. [端點與核心表對應總表](#6-端點與核心表對應總表)
7. [附錄 A：本文件補充決策清單](#附錄-a本文件補充決策清單)

---

## 1. API 總覽與慣例

### 1.1 Base path 與版本策略

- Base path：`/api/v1`
- 版本策略：**URI 路徑版本**（`/api/v1`、日後 `/api/v2`）。**【本文件補充決策 D1】** 選擇 URI 版本而非 header 版本，因購物網站後端串接簡單、除錯時 log 直接可見版本。
- 所有請求與回應 body 皆為 `application/json; charset=utf-8`。
- 所有連線強制 TLS（見 CLAUDE.md：傳輸安全 = TLS + API Key）。非 TLS 連線一律拒絕。

### 1.2 租戶識別與認證（API Key）

- 每個購物網站租戶對應 `tenants` 表一列，持有一組 API Key 與其 `rp_id`。
- 認證 header **【本文件補充決策 D2】**：

  | Header | 必填 | 說明 |
  |---|---|---|
  | `X-API-Key` | 是 | 租戶 API Key。FIDO 伺服器以此查 `tenants` 決定租戶，**租戶身分完全由 API Key 決定**，request body / path 內不接受任意 `tenant_id` 覆寫。 |
  | `X-Tenant-Id` | 否 | 選填的交叉檢查。若帶入且與 API Key 對應租戶不一致 → `403 TENANT_MISMATCH`。 |
  | `X-Request-Id` | 否 | 呼叫端自帶的追蹤 ID；伺服器會原樣回填至錯誤回應的 `traceId` 並寫入 `audit_log`。未帶則由伺服器產生。 |

- **RP ID 綁定**：`rp_id` 一律由伺服器依租戶查表決定（= 購物網站網域），呼叫端不得在 request 指定 RP ID；若前端送回的 `clientDataJSON` / attestation 中的 RP ID hash 與租戶 `rp_id` 不符 → `403 RP_ID_MISMATCH`。
- **Origin 綁定** **【本文件補充決策 D12】**（詳見 `docs/origin-binding.md`）：伺服器另比對 `clientDataJSON.origin` 是否在該租戶的允許 origin 清單。origin 有兩種合法形態，皆比對同一份租戶允許清單：
  - **Web origin**（瀏覽器情境，v1 基準）：如 `https://shop.example.com`，來源為 `tenants.expected_origin`。
  - **App origin**（原生 App 情境，opt-in）：`android:apk-key-hash:<base64url>`，來源為 `tenant_app_bindings` 表 active 列的 `apk_key_hash_origin`（租戶完成 Digital Asset Links onboarding 後由平台登錄）。純瀏覽器租戶不需登錄任何 App binding。
  - origin 不在允許清單 → `403 ORIGIN_NOT_ALLOWED`（與 RP ID 不符的 `RP_ID_MISMATCH` 區分，便於購物網站分辨錯因）。
- 缺少或無效 API Key → `401 UNAUTHENTICATED`。API Key 對應租戶被停用 → `403 TENANT_DISABLED`。
- **【本文件補充決策 D3】** 全域速率限制：每租戶預設 100 TPS（對齊 CLAUDE.md 峰值容量目標），超過回 `429 RATE_LIMITED`，帶 `Retry-After` header。

> 端點 3.3（JWKS）與 health 端點為**公開端點**，不需 API Key。

### 1.3 短時效自簽 JWT（session 交接）

- 用途：登入成功後，FIDO 伺服器簽發一枚短時效 JWT 交給購物網站，作為「此使用者剛通過 FIDO 硬體驗證」的憑證，供購物網站建立自己的登入 session。
- **FIDO 伺服器不是身分來源**：JWT 僅證明「FIDO 驗證通過」，購物網站仍須以自己既有帳密系統為身分權威（對齊 CLAUDE.md）。JWT 不得被當成帳號救援用途。
- 演算法 **【本文件補充決策 D4】**：`ES256`（EC P-256），私鑰保存在 FIDO 伺服器。
- 有效期 **【本文件補充決策 D5】**：`exp = iat + 120 秒`。理由：僅供一次性交接、立即被購物網站後端換成自家 session，短效降低轉發竊用風險；60 秒過短容易因時鐘偏差誤判，故取 120 秒。
- Claims：

  | claim | 型別 | 說明 |
  |---|---|---|
  | `iss` | string | FIDO 伺服器識別（設定值，如 `https://fido.example.internal`） |
  | `aud` | string | 目標租戶的 `rp_id`（購物網站網域） |
  | `sub` | string | 購物網站的使用者識別 `external_user_id` |
  | `tid` | string | 租戶 ID |
  | `cid` | string | 本次驗證所用 `credential_id`（base64url） |
  | `did` | string | 本次驗證所用 `device_id`（`bound_devices`） |
  | `amr` | string[] | 認證方式，固定 `["fido","hwk"]`（hardware key） |
  | `auth_time` | number | 驗證完成的 epoch 秒 |
  | `iat` / `exp` | number | 簽發 / 到期，`exp - iat = 120` |
  | `jti` | string | 一次性 token ID；購物網站**應**做一次性消費防重放 |

- 驗證方式：購物網站以端點 3.3 的 JWKS 公鑰驗簽，並自行校驗 `iss` / `aud`（= 自己網域）/ `exp` / `jti` 未用過。

### 1.4 通用錯誤格式與錯誤碼

所有非 2xx 回應統一格式：

```json
{
  "error": {
    "code": "CHALLENGE_EXPIRED",
    "message": "The challenge has expired, please request a new one.",
    "traceId": "req-7f3a...",
    "details": {}
  }
}
```

- `code`：機器可讀常數（見下表）；`message`：人類可讀，不保證穩定，不得程式判斷；`details`：選填補充。

通用錯誤碼：

| HTTP | code | 情境 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 欄位缺漏 / 格式錯誤 |
| 400 | `CHALLENGE_EXPIRED` | challenge 超過 60 秒或已被消費 |
| 400 | `CHALLENGE_NOT_FOUND` | `ceremony_id` 不存在 |
| 401 | `UNAUTHENTICATED` | API Key 缺失或無效 |
| 403 | `TENANT_MISMATCH` | `X-Tenant-Id` 與 API Key 不符 |
| 403 | `TENANT_DISABLED` | 租戶停用 |
| 403 | `RP_ID_MISMATCH` | RP ID hash 與租戶 `rp_id` 不符 |
| 403 | `ORIGIN_NOT_ALLOWED` | `clientDataJSON.origin` 不在租戶允許清單（web origin 於 `tenants.expected_origin`；app origin `android:apk-key-hash:...` 於 `tenant_app_bindings`）。**【D12】** 見 §1.2 Origin 綁定與 `docs/origin-binding.md` |
| 404 | `NOT_FOUND` | 路由 / 資源路徑不存在（例：未知端點）。**注意：使用者或裝置「存在與否」一律不以 404 表達**，見 D7 防列舉策略。 |
| 409 | `CREDENTIAL_ALREADY_EXISTS` | 同一 credential 已註冊 |
| 422 | `ATTESTATION_INVALID` | attestation 物件解析 / 簽章失敗 |
| 422 | `ATTESTATION_CHAIN_INVALID` | Android Key Attestation 憑證鏈驗證失敗 |
| 422 | `HARDWARE_SECURITY_NOT_MET` | 未達 TEE/StrongBox 要求 |
| 422 | `ASSERTION_INVALID` | assertion 簽章驗證失敗 |
| 422 | `SIGN_COUNTER_REGRESSION` | sign counter 倒退，已自動撤銷 |
| 422 | `CREDENTIAL_REVOKED` | 使用的 credential 已被撤銷 |
| 429 | `RATE_LIMITED` | 超過速率限制 |
| 500 | `INTERNAL_ERROR` | 伺服器內部錯誤 |

**【本文件補充決策 D6】** WebAuthn ceremony 驗證失敗使用 `422 Unprocessable Entity`（請求格式正確但語意驗證未過），與 `400`（格式錯誤）區分，方便呼叫端分流處理。

### 1.5 資料編碼慣例

- 所有二進位欄位（`challenge`、`credential id`、`rawId`、`clientDataJSON`、`attestationObject`、`authenticatorData`、`signature`、`userHandle`、public key 等）一律 **base64url（無 padding）** 字串。
- 時間欄位一律 ISO 8601 UTC（如 `2026-07-21T08:00:00Z`），除 JWT 內 claims 依 RFC 7519 用 epoch 秒。
- `ceremony_id`：伺服器產生的不透明字串，關聯一次註冊 / 登入 ceremony 與其 `auth_challenges` 列。

---

## 2. 註冊流程 API（WebAuthn Attestation Ceremony）

### 2.1 產生註冊 challenge

`POST /api/v1/registration/options`

由購物網站後端在使用者（已用帳密登入）要求「新增 FIDO 裝置」時呼叫。

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `externalUserId` | string | 是 | 購物網站的使用者 ID（身分權威來源在購物網站） |
| `displayName` | string | 否 | WebAuthn `user.displayName`，UI 顯示用 |
| `deviceLabel` | string | 否 | 使用者為此裝置取的名稱，成功註冊後寫入 `bound_devices.device_name` |

**Response 200**

回傳可直接餵給前端 `navigator.credentials.create()` 的 `publicKeyCredentialCreationOptions`，外加 `ceremonyId`。

```json
{
  "ceremonyId": "reg_9c2f...",
  "publicKey": {
    "rp":   { "id": "shop.example.com", "name": "Example Shop" },
    "user": { "id": "<base64url user_handle>", "name": "user@example", "displayName": "..." },
    "challenge": "<base64url 32-byte>",
    "pubKeyCredParams": [ { "type": "public-key", "alg": -7 }, { "type": "public-key", "alg": -257 } ],
    "timeout": 60000,
    "attestation": "direct",
    "authenticatorSelection": {
      "authenticatorAttachment": "platform",
      "residentKey": "required",
      "requireResidentKey": true,
      "userVerification": "required"
    },
    "excludeCredentials": [ { "type": "public-key", "id": "<base64url>", "transports": ["internal"] } ]
  }
}
```

- **60 秒時效如何體現**：`publicKey.timeout = 60000`（毫秒）供前端 UI；同時伺服器在 `auth_challenges` 寫入 `expires_at = now + 60s`，端點 2.2 以伺服器端 `expires_at` 為準強制檢核（前端 timeout 只是提示，不可信）。
- `rp.id` 一律伺服器依租戶填入；`attestation:"direct"` 以取得 Android Key Attestation；`authenticatorAttachment:"platform"` + `residentKey:"required"` 對齊情境 A 同裝置 discoverable credential。
- `excludeCredentials`：帶入該使用者現有 active credential，避免同裝置重複註冊。
- **user_handle**：伺服器針對 (租戶, `externalUserId`) 產生穩定隨機 `user_handle`（首次註冊時建立 `fido_user_ref` 列並存入），供跨裝置的同一使用者共用。

**主要錯誤**：`401 UNAUTHENTICATED`、`403 TENANT_DISABLED`、`400 VALIDATION_ERROR`、`429 RATE_LIMITED`。

**核心表對應**：讀 `tenants`（rp_id）；upsert `fido_user_ref`（user_handle）；insert `auth_challenges`（type=`REGISTRATION`, expires_at, ceremony_id）；`audit_log` 記錄 `REG_OPTIONS_ISSUED`。

---

### 2.2 提交註冊結果

`POST /api/v1/registration/result`

前端 `create()` 完成後，購物網站後端把結果轉交本端點驗證。

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `ceremonyId` | string | 是 | 2.1 回傳值 |
| `externalUserId` | string | 是 | 與 2.1 相同使用者 |
| `credential.id` | string | 是 | base64url credential id |
| `credential.rawId` | string | 是 | base64url |
| `credential.type` | string | 是 | 固定 `public-key` |
| `credential.response.clientDataJSON` | string | 是 | base64url |
| `credential.response.attestationObject` | string | 是 | base64url，含 Android Key Attestation |
| `credential.response.transports` | string[] | 否 | 如 `["internal"]` |
| `deviceLabel` | string | 否 | 覆寫 2.1 的裝置名稱 |

**伺服器驗證步驟（合約語意）**

1. 依 `ceremonyId` 取 `auth_challenges`；不存在 → `400 CHALLENGE_NOT_FOUND`；已過 `expires_at` 或已消費 → `400 CHALLENGE_EXPIRED`（前端須依 CLAUDE.md「逾時自動重新申請」重跑 2.1）。
2. 標準 WebAuthn attestation 驗證：`clientDataJSON.type=webauthn.create`、challenge 相符、RP ID hash 與租戶 `rp_id` 相符（不符 → `403 RP_ID_MISMATCH`）、`clientDataJSON.origin` 在租戶允許清單（不符 → `403 ORIGIN_NOT_ALLOWED`；允許清單含 web origin 與 `tenant_app_bindings` 的 app origin，見 §1.2 / `docs/origin-binding.md`）。
3. **Android Key Attestation 憑證鏈驗證**：解析 attestation 憑證鏈至 Google 硬體 attestation root，驗證有效性與撤銷狀態；失敗 → `422 ATTESTATION_CHAIN_INVALID`。
4. **TEE/StrongBox 檢核**：檢查 attestation extension 的 `securityLevel`；未達 `TRUSTED_ENVIRONMENT`（TEE）或 `STRONG_BOX` → `422 HARDWARE_SECURITY_NOT_MET`，**拒絕註冊**（對齊 CLAUDE.md 強制硬體安全區）。`details` 內回傳實際偵測到的 level 供客服判讀。
5. 若該 credential 已存在 → `409 CREDENTIAL_ALREADY_EXISTS`。
6. 全數通過 → 建立 `fido_credentials` 與 `bound_devices`，challenge 標記已消費。

**Response 201**

```json
{
  "credentialId": "<base64url>",
  "deviceId": "dev_5a1b...",
  "device": {
    "deviceName": "我的 Pixel 8",
    "aaguid": "<uuid>",
    "securityLevel": "STRONG_BOX",
    "createdAt": "2026-07-21T08:00:03Z"
  },
  "signCount": 0
}
```

**主要錯誤**：`400 CHALLENGE_EXPIRED/CHALLENGE_NOT_FOUND/VALIDATION_ERROR`、`403 RP_ID_MISMATCH/ORIGIN_NOT_ALLOWED`、`422 ATTESTATION_INVALID/ATTESTATION_CHAIN_INVALID/HARDWARE_SECURITY_NOT_MET`、`409 CREDENTIAL_ALREADY_EXISTS`。

**核心表對應**：讀/消費 `auth_challenges`；讀 `tenant_app_bindings`（若 origin 為 app origin，比對其 `apk_key_hash_origin`）；insert `fido_credentials`（credential_id, public_key, sign_count=0, aaguid, transports, status=`ACTIVE`）；insert `bound_devices`（device_name, model, os_version, security_level, attestation 摘要）；`audit_log` 記 `REG_SUCCESS` 或失敗原因，`detail` 內記本次 ceremony 的 origin 來源型別（`originType` = `WEB` / `NATIVE_APP`，**【本文件補充決策 D13】**，用既有 JSON `detail` 欄位、無 schema 變更）。

---

## 3. 登入流程 API（WebAuthn Assertion Ceremony）

### 3.1 產生登入 challenge

`POST /api/v1/authentication/options`

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `externalUserId` | string | 否 | 帶入則回 `allowCredentials`（已知使用者）；省略則走 discoverable / usernameless（前端由 Credential Manager 選帳號）。 |

**Response 200**

```json
{
  "ceremonyId": "auth_3d7e...",
  "publicKey": {
    "challenge": "<base64url 32-byte>",
    "timeout": 60000,
    "rpId": "shop.example.com",
    "userVerification": "required",
    "allowCredentials": [ { "type": "public-key", "id": "<base64url>", "transports": ["internal"] } ]
  }
}
```

- 60 秒時效體現方式同 2.1（`timeout=60000` + 伺服器 `auth_challenges.expires_at=now+60s`）。
- **【本文件補充決策 D7】** 為避免帳號列舉（account enumeration），當 `externalUserId` 帶入卻查無 active credential（或使用者根本不存在）時，仍回 200 且 `allowCredentials` 為空陣列（讓 ceremony 自然失敗），而非以 404 洩漏帳號存在與否。此防列舉風格在全站一致：登入 options（3.1）、裝置列表（4.1）、裝置撤銷（4.2）皆不使用 404。

**核心表對應**：讀 `fido_user_ref` / `fido_credentials`（active 憑證清單）；insert `auth_challenges`（type=`AUTHENTICATION`）；`audit_log` 記 `AUTH_OPTIONS_ISSUED`。

---

### 3.2 提交登入結果

`POST /api/v1/authentication/result`

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `ceremonyId` | string | 是 | 3.1 回傳值 |
| `credential.id` | string | 是 | base64url credential id |
| `credential.rawId` | string | 是 | base64url |
| `credential.type` | string | 是 | `public-key` |
| `credential.response.clientDataJSON` | string | 是 | base64url |
| `credential.response.authenticatorData` | string | 是 | base64url |
| `credential.response.signature` | string | 是 | base64url |
| `credential.response.userHandle` | string | 否 | base64url，discoverable 流程會帶 |

**伺服器驗證步驟（合約語意）**

1. 依 `ceremonyId` 取 challenge；過期/不存在 → `400 CHALLENGE_EXPIRED / CHALLENGE_NOT_FOUND`。
2. 以 credential id 查 `fido_credentials`；查無 → `422 ASSERTION_INVALID`；狀態非 ACTIVE → `422 CREDENTIAL_REVOKED`。
3. 標準 assertion 驗證：`clientDataJSON.type=webauthn.get`、challenge 相符、RP ID hash 相符（不符 → `403 RP_ID_MISMATCH`）、`clientDataJSON.origin` 在租戶允許清單（不符 → `403 ORIGIN_NOT_ALLOWED`，見 §1.2 / `docs/origin-binding.md`）、UV flag 為真、以儲存的 public key 驗 signature；失敗 → `422 ASSERTION_INVALID`。
4. **Sign counter 檢查（含自動撤銷）**：取 `authenticatorData` 的 counter 與 `fido_credentials.sign_count` 比較：
   - `new > stored` → 正常，更新 `sign_count = new`。
   - `new == 0 && stored == 0` → 視為該 authenticator 不提供計數，放行不更新（合約允許）。
   - `new <= stored`（且非上一種情形）→ **判定 sign counter 倒退**：立即將該 `fido_credentials` 與對應 `bound_devices` 標記 `REVOKED`，寫 `audit_log`（`AUTO_REVOKE_COUNTER_REGRESSION`），回 `422 SIGN_COUNTER_REGRESSION`。此為 CLAUDE.md「簽章異常自動撤銷」的落地行為，代表可能有金鑰複製/仿冒。
5. 全數通過 → 更新 `sign_count`、`last_used_at`，簽發 session JWT（見 1.3）。

**Response 200**

```json
{
  "verified": true,
  "externalUserId": "u-10023",
  "credentialId": "<base64url>",
  "deviceId": "dev_5a1b...",
  "session": {
    "token": "<JWT>",
    "tokenType": "Bearer",
    "expiresIn": 120
  }
}
```

**主要錯誤**：`400 CHALLENGE_EXPIRED/CHALLENGE_NOT_FOUND`、`403 RP_ID_MISMATCH/ORIGIN_NOT_ALLOWED`、`422 ASSERTION_INVALID/CREDENTIAL_REVOKED/SIGN_COUNTER_REGRESSION`。

**核心表對應**：讀/消費 `auth_challenges`；讀 `tenant_app_bindings`（若 origin 為 app origin）；讀/更新 `fido_credentials`（sign_count、status）；更新 `bound_devices`（last_used_at，或撤銷時 status）；`audit_log` 記 `AUTH_SUCCESS` / `AUTO_REVOKE_COUNTER_REGRESSION` / `AUTH_FAIL`，`detail` 內記本次 origin 來源型別（`originType` = `WEB` / `NATIVE_APP`，**【D13】**）。JWT 不落庫（短效），僅 `jti` 可選記於 audit。

---

### 3.3 JWKS 公鑰端點

`GET /api/v1/.well-known/jwks.json`

- **公開端點**，不需 API Key（購物網站需能取得公鑰驗 JWT 簽章）。
- 回傳 FIDO 伺服器簽發 JWT（1.3）用的 EC 公鑰集合，供密鑰輪替。**【本文件補充決策 D8】** 提供 JWKS 端點以支援公鑰輪替，`kid` 對應 JWT header 的 `kid`。

**Response 200**

```json
{ "keys": [ { "kty": "EC", "crv": "P-256", "kid": "2026-fido-1", "x": "...", "y": "...", "use": "sig", "alg": "ES256" } ] }
```

**核心表對應**：無（讀伺服器金鑰設定）。

---

## 4. 裝置管理 API

> 對應 CLAUDE.md「允許使用者註冊多台裝置，提供管理介面」。這些端點由購物網站後端（使用者已帳密登入的裝置管理頁）呼叫。

### 4.1 列出使用者已註冊裝置

`GET /api/v1/users/{externalUserId}/devices`

**Query 參數**

| 參數 | 型別 | 必填 | 預設 | 說明 |
|---|---|---|---|---|
| `status` | string | 否 | `ACTIVE` | `ACTIVE` / `REVOKED` / `ALL` |
| `limit` | int | 否 | 50 | **【本文件補充決策 D9】** 分頁上限，最大 100 |
| `cursor` | string | 否 | — | 游標式分頁（回傳 `nextCursor`）。單一使用者裝置通常個位數，分頁僅為保守設計。 |

**Response 200**

```json
{
  "externalUserId": "u-10023",
  "devices": [
    {
      "deviceId": "dev_5a1b...",
      "deviceName": "我的 Pixel 8",
      "model": "Pixel 8",
      "osVersion": "Android 14",
      "securityLevel": "STRONG_BOX",
      "aaguid": "<uuid>",
      "credentialId": "<base64url>",
      "status": "ACTIVE",
      "createdAt": "2026-07-21T08:00:03Z",
      "lastUsedAt": "2026-07-21T09:12:44Z"
    }
  ],
  "nextCursor": null
}
```

**主要錯誤**：`401 UNAUTHENTICATED`、`429 RATE_LIMITED`。

- **【本文件補充決策 D7，防列舉一致化】** 使用者不存在（尚未建立 `fido_user_ref`）時**不回 404**，而是比照 5.1「查詢 FIDO 綁定狀態」回 200 + `devices: []`（空陣列、`nextCursor: null`）。全站登入與裝置管理 API 統一採防帳號列舉風格，不以 HTTP 狀態碼洩漏使用者是否存在。

**核心表對應**：讀 `fido_user_ref` join `bound_devices` + `fido_credentials`。

---

### 4.2 撤銷 / 刪除裝置

`DELETE /api/v1/users/{externalUserId}/devices/{deviceId}`

- 對應 CLAUDE.md「使用者主動撤銷」。
- **【本文件補充決策 D10】** 採**軟刪除**：將 `bound_devices` 與其 `fido_credentials` 標記 `status=REVOKED`（`revoked_at`, `revoked_reason=USER_REQUEST`），**不實體刪列**，以保留稽核紀錄（對齊 CLAUDE.md 稽核保留 1 年）。呼叫端如需「永久刪除」語意仍以撤銷呈現。
- **不可移除最後一個裝置的護欄**：FIDO 是加掛選項、帳密永久保留，因此**允許**撤銷到 0 個裝置（使用者回到純帳密登入），不做「至少保留一個 FIDO」的阻擋。此點與 CLAUDE.md「帳密永久保留、不可單獨用 FIDO 取代帳密」一致。

**Response 200**

```json
{ "deviceId": "dev_5a1b...", "status": "REVOKED", "revokedAt": "2026-07-21T10:00:00Z" }
```

**主要錯誤**：`401 UNAUTHENTICATED`、`429 RATE_LIMITED`。

- **【本文件補充決策 D7，防列舉一致化】** 冪等 no-op 設計：不論 `deviceId` 查無、不屬於該 `externalUserId`、或已是 `REVOKED`，**一律回 200**（狀態呈現為 `REVOKED`），**不回 404**，以避免呼叫端藉狀態碼探測某個 `deviceId` 是否存在或屬於他人。伺服器僅在該裝置確實屬於該租戶+該使用者且為 ACTIVE 時才真正執行撤銷並寫稽核；其餘情況視為 no-op（撤銷嘗試仍寫入 `audit_log`，含 `matched:false` 供事後鑑識，但回應對呼叫端無差異）。

**核心表對應**：更新 `bound_devices` + `fido_credentials`（status=REVOKED）；`audit_log` 記 `DEVICE_REVOKED_BY_USER`（no-op 時記 `DEVICE_REVOKE_NOOP`）。

---

## 5. 查詢類 API

### 5.1 查詢使用者 FIDO 綁定狀態

`GET /api/v1/users/{externalUserId}/fido-status`

供購物網站前端顯示「是否已啟用 FIDO / 可否用 FIDO 登入」。

**Response 200**

```json
{
  "externalUserId": "u-10023",
  "enrolled": true,
  "activeDeviceCount": 2,
  "canUseFido": true
}
```

- 使用者不存在（尚未建立 `fido_user_ref`）→ 回 200 且 `enrolled=false, activeDeviceCount=0, canUseFido=false`（不回 404，方便前端直接判斷）。
- `canUseFido = activeDeviceCount > 0`。

**核心表對應**：讀 `fido_user_ref` + `fido_credentials`(status=ACTIVE 計數)。

---

### 5.2 稽核事件查詢（客服用，選配）

`GET /api/v1/users/{externalUserId}/audit-events`

- **【本文件補充決策 D11】** 提供唯讀的稽核查詢，供客服在「帳號救援以客服人工為後盾」時查閱使用者的 FIDO 操作歷程。第一版可延後實作，但先在合約保留。
- Query：`from`, `to`（ISO8601）, `type`（事件類型過濾）, `limit`（預設 50，最大 100）, `cursor`。

**Response 200**

```json
{
  "events": [
    { "eventId": "ev_...", "type": "AUTH_SUCCESS", "deviceId": "dev_5a1b...", "at": "2026-07-21T09:12:44Z", "detail": {} },
    { "eventId": "ev_...", "type": "AUTO_REVOKE_COUNTER_REGRESSION", "deviceId": "dev_5a1b...", "at": "2026-07-20T22:01:10Z", "detail": {} }
  ],
  "nextCursor": null
}
```

**核心表對應**：讀 `audit_log`（依租戶 + 使用者過濾，僅回本租戶資料）。

---

### 5.3 租戶已授權 App 清單（原生 App 情境，v1 無 API 端點）

**【本文件補充決策 D14】** 原生 App 情境（`docs/origin-binding.md` OB1）需要「租戶授權哪些原生 App（package + 簽章指紋）代表其網域」的資料，存於 `tenant_app_bindings` 表。經評估，**v1 不新增任何 REST 端點**管理此清單，理由：

1. **登錄流程 v1 採人工 onboarding**（origin-binding.md OB6）：租戶完成 `assetlinks.json` 部署後，把 App package + SHA-256 簽章指紋交給平台營運方，由營運方直接寫入 `tenant_app_bindings`（與「租戶開通、發放 API Key」同屬人工 onboarding 步驟）。中小規模、一租戶通常一支 App，人工足夠。
2. **不影響 ceremony 驗證正確性**：伺服器在 §2.2 / §3.2 驗證 origin 時，直接讀 `tenant_app_bindings` 比對，無需呼叫端提供任何額外欄位，故現有 ceremony 端點無需改動請求/回應結構。

日後若原生 App 租戶增多、需自助管理，再評估新增（唯讀查詢 `GET .../tenant/app-bindings` 或設定端點）；此擴充與時程列 origin-binding.md OB6，非 v1 範圍。屆時 `tenant_app_bindings.binding_uid` 即為對外識別（db-schema.md DB17 已預留）。

---

## 6. 端點與核心表對應總表

| # | Method | Path | 主要讀寫的表 |
|---|---|---|---|
| 2.1 | POST | `/api/v1/registration/options` | R `tenants`；U `fido_user_ref`；C `auth_challenges`；C `audit_log` |
| 2.2 | POST | `/api/v1/registration/result` | RU `auth_challenges`；R `tenant_app_bindings`(origin 為 app origin 時)；C `fido_credentials`,`bound_devices`；C `audit_log` |
| 3.1 | POST | `/api/v1/authentication/options` | R `fido_user_ref`,`fido_credentials`；C `auth_challenges`；C `audit_log` |
| 3.2 | POST | `/api/v1/authentication/result` | RU `auth_challenges`,`fido_credentials`,`bound_devices`；R `tenant_app_bindings`(origin 為 app origin 時)；C `audit_log` |
| 3.3 | GET | `/api/v1/.well-known/jwks.json` | （伺服器金鑰設定，無表） |
| 4.1 | GET | `/api/v1/users/{externalUserId}/devices` | R `fido_user_ref`,`bound_devices`,`fido_credentials` |
| 4.2 | DELETE | `/api/v1/users/{externalUserId}/devices/{deviceId}` | U `bound_devices`,`fido_credentials`；C `audit_log` |
| 5.1 | GET | `/api/v1/users/{externalUserId}/fido-status` | R `fido_user_ref`,`fido_credentials` |
| 5.2 | GET | `/api/v1/users/{externalUserId}/audit-events` | R `audit_log` |

R=讀 C=新增 U=更新。所有查詢皆隱含以 API Key 對應租戶作 `tenant_id` 隔離。

---

## 附錄 A：本文件補充決策清單

以下為 CLAUDE.md 未覆蓋、由本文件先行決定、待人工複核回填 CLAUDE.md 的項目：

| 編號 | 決策 | 理由 |
|---|---|---|
| D1 | 採 URI 路徑版本 `/api/v1` | 串接與 log 除錯直觀 |
| D2 | 認證 header：`X-API-Key`（必）、`X-Tenant-Id`（選，交叉檢查）、`X-Request-Id`（選，追蹤）；租戶身分只由 API Key 決定 | 明確 header 命名與防租戶偽冒 |
| D3 | 每租戶速率限制預設 100 TPS，超過回 429 + `Retry-After` | 對齊 CLAUDE.md 峰值 ≤100 TPS |
| D4 | session JWT 演算法 `ES256`（EC P-256） | 短簽章、地端自持私鑰 |
| D5 | session JWT 有效期 120 秒 | 一次性交接、抗時鐘偏差同時抗轉發竊用 |
| D6 | WebAuthn ceremony 驗證失敗用 `422`，格式錯誤用 `400` | 呼叫端易分流 |
| D7 | 全站一致防列舉：登入 options（3.1）、裝置列表（4.1）回 200 + 空清單；裝置撤銷（4.2）回 200 冪等 no-op；一律不以 404 洩漏使用者/裝置是否存在（使用者已複核選定） | 防帳號列舉，避免呼叫端藉狀態碼探測帳號/裝置存在性 |
| D8 | 提供 `/.well-known/jwks.json` 公開端點與 `kid` 輪替 | 支援公鑰輪替 |
| D9 | 列表端點分頁：`limit` 預設 50 / 最大 100，游標式 `cursor` | 保守分頁 |
| D10 | 裝置撤銷採軟刪除（status=REVOKED，不實體刪列） | 保留 1 年稽核 |
| D11 | 新增 5.2 稽核事件唯讀查詢（第一版可延後實作） | 支援客服人工帳號救援後盾 |
| D12 | Origin 綁定：`clientDataJSON.origin` 比對租戶允許清單（web origin 於 `tenants.expected_origin`、app origin 於 `tenant_app_bindings`）；不符回 `403 ORIGIN_NOT_ALLOWED`（與 `RP_ID_MISMATCH` 區分） | 對齊 origin-binding.md OB1/OB4，人類已拍板；防釣魚並支援原生 App opt-in |
| D13 | `registration/result` / `authentication/result` 於 `audit_log.detail` 記 `originType`(`WEB`/`NATIVE_APP`) | origin-binding.md OB5，人類已拍板；事後鑑識登入來源，用既有 JSON 欄位無 schema 變更 |
| D14 | 租戶 App 授權清單 v1 不新增 REST 端點，採人工 onboarding 寫 `tenant_app_bindings` | origin-binding.md OB6，人類已拍板；中小規模人工足夠、不影響 ceremony 驗證 |
| D-附 | 允許使用者撤銷至 0 個 FIDO 裝置（回退純帳密） | 對齊帳密永久保留、FIDO 為加掛選項 |

> 複核通過後，建議將 D2/D3/D4/D5/D10 等影響架構的項目回填至 CLAUDE.md「已確認的關鍵架構決策」表，並將本合約交接 dev-engineer 進入實作。D12/D13/D14 已由人類拍板（origin-binding.md OB1/OB4/OB5/OB6），對應 `tenant_app_bindings` 表已定案於 db-schema.md 第 9 節。
