# API 規格書與串接手冊

> 適用對象：採用廠商的後端工程師，負責讓貴公司購物網站後端以 server-to-server 方式串接 `fido-server`。
>
> 本手冊是「可直接照做」的串接指南。完整的合約權威定義見 [`../api-contract.md`](../api-contract.md)；本文件聚焦實作要點、真實範例、責任邊界與錯誤處理。部署設定見 [`environment-setup-guide.md`](environment-setup-guide.md)，功能限制見 [`technical-limitations.md`](technical-limitations.md)。

---

## 1. 串接前你必須先理解的三件事

1. **所有 API 呼叫方都是貴公司的「後端」，不是前端。** 手機上的 WebAuthn ceremony（`navigator.credentials.create()` / `.get()`）由瀏覽器 / 系統 Credential Manager 執行，前端把結果交回貴公司後端，再由**貴公司後端**以 server-to-server 呼叫本 API。前端絕不直接呼叫 `fido-server`（前端也拿不到 API Key）。
2. **`fido-server` 只驗證「租戶身分」與「FIDO 密碼學正確性」，不驗證「終端使用者是誰」。** `X-API-Key` 決定是哪個租戶，但伺服器**無從得知**某次呼叫是否真由某位使用者本人發起。「這次操作對應的使用者是誰」完全由貴公司後端負責把關（見第 6 節 IDOR，務必讀完）。
3. **登入成功後拿到的 session JWT，要靠貴公司後端自己驗簽，不要相信回應裡的 `verified` 欄位。** 真正可信的是「用 JWKS 公鑰驗過簽章、且 issuer/audience/exp 都對」的 JWT（見第 4 節）。

---

## 2. 通用慣例

### 2.1 Base path 與傳輸

- Base path：`/api/v1`（URI 路徑版本）。
- 所有 request / response body 皆為 `application/json; charset=utf-8`。
- 全程強制 TLS。正式環境絕不可走純 HTTP。
- 所有二進位欄位（`challenge`、`credential id`、`rawId`、`clientDataJSON`、`attestationObject`、`authenticatorData`、`signature`、`userHandle`、public key 等）一律為 **base64url（無 padding）** 字串。
- 時間欄位為 ISO 8601 UTC（如 `2026-07-21T08:00:00Z`）；JWT 內 claims 依 RFC 7519 用 epoch 秒。

### 2.2 認證 Header

| Header | 必填 | 說明 |
|---|---|---|
| `X-API-Key` | 是 | 租戶 API Key。伺服器以此決定租戶。租戶身分**完全**由此決定，body / path 內任何 `tenant_id` 都不會覆寫它。 |
| `X-Tenant-Id` | 否 | 選填交叉檢查。若帶入且與 API Key 對應租戶不一致 → `403 TENANT_MISMATCH`。 |
| `X-Request-Id` | 否 | 呼叫端自帶的追蹤 ID；會原樣回填至錯誤回應的 `traceId` 並寫入 `audit_log`。未帶則由伺服器產生。建議每次呼叫都帶，便於跨系統追蹤。 |

- 缺少 / 無效 API Key → `401 UNAUTHENTICATED`。
- 租戶被停用 → `403 TENANT_DISABLED`。
- 公開端點例外：`GET /api/v1/.well-known/jwks.json` 與 `/actuator/*` 不需 API Key。

### 2.3 速率限制

- 每租戶預設 **100 TPS**（可於租戶設定逐一覆寫）。超過回 `429 RATE_LIMITED`，並帶 `Retry-After` header（秒）。
- 貴公司後端應對 `429` 做退避重試，並避免把使用者的高頻操作直接放大成對本 API 的高頻呼叫。

### 2.4 RP ID / Origin 綁定

- `rp_id`（= 貴公司網域）由伺服器依租戶查表決定，**呼叫端不得在 request 指定**。
- 若前端送回的 attestation / assertion 中的 RP ID hash 與租戶 `rp_id` 不符 → `403 RP_ID_MISMATCH`。
- 若 `clientDataJSON.origin` 不在該租戶允許清單 → `403 ORIGIN_NOT_ALLOWED`。允許清單 = 租戶 Web origin（`expected_origin`）∪ 該租戶已登錄的原生 App origin（`tenant_app_bindings`，僅 opt-in 租戶有）。詳見第 7 節與 [`../origin-binding.md`](../origin-binding.md)。

---

## 3. 端點清單與串接流程

完整端點清單：

| # | Method | Path | 用途 |
|---|---|---|---|
| 2.1 | POST | `/api/v1/registration/options` | 產生註冊 challenge |
| 2.2 | POST | `/api/v1/registration/result` | 提交註冊結果（驗證 attestation） |
| 3.1 | POST | `/api/v1/authentication/options` | 產生登入 challenge |
| 3.2 | POST | `/api/v1/authentication/result` | 提交登入結果（驗證 assertion、簽發 JWT） |
| 3.3 | GET | `/api/v1/.well-known/jwks.json` | JWKS 公鑰（公開端點） |
| 4.1 | GET | `/api/v1/users/{externalUserId}/devices` | 列出使用者已註冊裝置 |
| 4.2 | DELETE | `/api/v1/users/{externalUserId}/devices/{deviceId}` | 撤銷裝置（軟刪除） |
| 5.1 | GET | `/api/v1/users/{externalUserId}/fido-status` | 查詢使用者 FIDO 綁定狀態 |
| 5.2 | GET | `/api/v1/users/{externalUserId}/audit-events` | 稽核事件查詢（客服用，v1 可延後） |

### 3.1 註冊流程（新增一台 FIDO 裝置）

前提：使用者已用貴公司**既有帳密系統**登入（FIDO 是加掛選項，不取代帳密）。

```
[使用者(已帳密登入)]        [貴公司前端]           [貴公司後端]              [fido-server]
        │  按「新增 FIDO 裝置」  │                        │                         │
        │──────────────────────>│                        │                         │
        │                       │  請求註冊 options       │                         │
        │                       │───────────────────────>│  POST /registration/options
        │                       │                        │  externalUserId(取自後端 session)
        │                       │                        │────────────────────────>│
        │                       │                        │  publicKey + ceremonyId │
        │                       │  <────────────────────────────────────────────── │
        │  navigator.credentials.create(publicKey)        │                         │
        │  (系統 Credential Manager → 手機硬體產生金鑰)   │                         │
        │──────────────────────>│  attestation 結果       │                         │
        │                       │───────────────────────>│  POST /registration/result
        │                       │                        │  ceremonyId + credential+ externalUserId
        │                       │                        │────────────────────────>│
        │                       │                        │  201 credentialId/deviceId
        │                       │  <────────────────────────────────────────────── │
```

要點：

1. `POST /registration/options` 的 `externalUserId` **必須取自貴公司後端自己的登入 session**（見第 6 節）。
2. 把回應的 `publicKey` 原樣交前端餵給 `navigator.credentials.create()`；`ceremonyId` 由後端保留。
3. 前端拿到 attestation 後交回後端，後端以 `POST /registration/result` 提交（附上同一個 `ceremonyId` 與同一 `externalUserId`）。
4. challenge 60 秒時效以伺服器端為準；逾時回 `400 CHALLENGE_EXPIRED`，前端須重新走 options。

### 3.2 登入流程

```
[使用者]                 [貴公司前端]            [貴公司後端]            [fido-server]
   │  選 FIDO 登入          │                       │                        │
   │──────────────────────>│  請求登入 options      │                        │
   │                       │──────────────────────>│  POST /authentication/options
   │                       │                       │───────────────────────>│
   │                       │                       │  publicKey + ceremonyId│
   │                       │  <─────────────────────────────────────────────│
   │  navigator.credentials.get(publicKey) → 生物辨識                        │
   │──────────────────────>│  assertion 結果        │                        │
   │                       │──────────────────────>│  POST /authentication/result
   │                       │                       │───────────────────────>│
   │                       │                       │  200 verified + session.token(JWT)
   │                       │  <─────────────────────────────────────────────│
   │                       │       [貴公司後端驗 JWT → 建立自家登入 session] │
```

要點：

1. `authentication/options` 的 `externalUserId` 選填：帶入則回該使用者的 `allowCredentials`；省略則走 usernameless（由 Credential Manager 選帳號）。
2. 登入成功回應含 `session.token`（JWT）。**貴公司後端必須自行驗證此 JWT**（第 4 節），驗過才建立自家登入 session，不可只看 `verified: true`。

### 3.3 裝置管理流程

- 列出裝置：`GET /api/v1/users/{externalUserId}/devices`（預設只回 `ACTIVE`，可帶 `status=ALL`）。
- 撤銷裝置：`DELETE /api/v1/users/{externalUserId}/devices/{deviceId}`（軟刪除，狀態轉 `REVOKED`）。
- 兩者的 `{externalUserId}` 都在 URL 路徑，**必須來自後端登入 session**（第 6 節）。

---

## 4. Session JWT 驗證步驟（登入成功後必做）

`fido-server` 登入成功回應中的 `session.token` 是一枚短時效 JWT，代表「此使用者剛通過 FIDO 硬體驗證」。它**不是**身分權威、不可當帳號救援用，只是一次性交接憑證，供貴公司後端建立自己的登入 session。

JWT 規格：

- 演算法：**ES256（EC P-256）**。
- 有效期：`exp = iat + 120 秒`。
- Header 帶 `kid`，對應 JWKS 端點裡的公鑰。

Claims：

| claim | 說明 |
|---|---|
| `iss` | fido-server 識別（= 部署設定 `fido.session-jwt.issuer`） |
| `aud` | 目標租戶的 `rp_id`（= 貴公司網域） |
| `sub` | 貴公司的使用者識別 `external_user_id` |
| `tid` | 租戶 ID（`tenant_uid`） |
| `cid` | 本次驗證所用 `credential_id`（base64url） |
| `did` | 本次驗證所用 `device_id` |
| `amr` | 固定 `["fido","hwk"]` |
| `auth_time` | 驗證完成的 epoch 秒 |
| `iat` / `exp` | 簽發 / 到期（差 120 秒） |
| `jti` | 一次性 token ID |

**貴公司後端驗證步驟（缺一不可）**：

1. 從 `GET /api/v1/.well-known/jwks.json` 取得公鑰集合（依 `kid` 對應），建議快取（參考範例快取 300 秒）。
2. 以對應公鑰驗 **ES256 簽章**；驗不過即拒絕。
3. 檢查 `iss` = 貴公司部署的 fido-server issuer（與部署設定一致）。
4. 檢查 `aud` = 貴公司自己的網域（`rp_id`）。
5. 檢查 `exp` 未過期（120 秒內）。
6. 檢查 `jti` **未被用過**（一次性消費，防重放）。
7. 全部通過，才以 `sub`（= `externalUserId`）建立貴公司自家登入 session。

> 參考範例 `shopping-site-reference` 的 `FidoSessionJwtValidator` 就是這個模式的具體實作：它刻意**不信任回應的 `verified` 欄位**，只信任自行驗過的 JWT。請照抄此模式。

---

## 5. 完整錯誤碼表

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

- `code`：機器可讀常數（見下表），程式判斷請用它。
- `message`：人類可讀，**不保證穩定，不得用於程式判斷**。
- `traceId`：對應 `X-Request-Id`，排查用。

完整錯誤碼（與 `fido-server` 的 `ErrorCode` 列舉、API 合約 §1.4 逐項對齊）：

| HTTP | code | 情境 | 貴公司後端建議處理 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 欄位缺漏 / 格式錯誤 | 修正請求 |
| 400 | `CHALLENGE_EXPIRED` | challenge 超過 60 秒或已被消費 | 重新走 options |
| 400 | `CHALLENGE_NOT_FOUND` | `ceremonyId` 不存在 | 重新走 options |
| 401 | `UNAUTHENTICATED` | API Key 缺失或無效 | 檢查 `X-API-Key` |
| 403 | `TENANT_MISMATCH` | `X-Tenant-Id` 與 API Key 不符 | 檢查 header |
| 403 | `TENANT_DISABLED` | 租戶被停用 | 聯絡平台營運方 |
| 403 | `RP_ID_MISMATCH` | RP ID hash 與租戶 `rp_id` 不符 | 檢查前端 rp.id 設定是否為貴公司網域 |
| 403 | `ORIGIN_NOT_ALLOWED` | `clientDataJSON.origin` 不在租戶允許清單 | 檢查 origin / 原生 App 綁定（第 7 節） |
| 404 | `NOT_FOUND` | 路由 / 資源路徑不存在（例：未知端點）。**注意：使用者或裝置「存在與否」永遠不以 404 表達**（見第 8 節防列舉） | 檢查 URL |
| 409 | `CREDENTIAL_ALREADY_EXISTS` | 同一 credential 已註冊 | 提示使用者該裝置已註冊 |
| 422 | `ATTESTATION_INVALID` | attestation 物件解析 / 簽章失敗 | 註冊失敗，提示重試 |
| 422 | `ATTESTATION_CHAIN_INVALID` | Android Key Attestation 憑證鏈驗證失敗 | 註冊失敗（裝置無法通過硬體憑證鏈） |
| 422 | `HARDWARE_SECURITY_NOT_MET` | 未達 TEE/StrongBox 硬體要求 | 該裝置硬體不符，無法註冊 FIDO |
| 422 | `ASSERTION_INVALID` | assertion 簽章驗證失敗 | 登入失敗，提示重試 |
| 422 | `SIGN_COUNTER_REGRESSION` | sign counter 倒退，該憑證已被自動撤銷 | 登入失敗；該裝置已被系統撤銷（疑似金鑰複製），引導改用帳密並重新註冊 |
| 422 | `CREDENTIAL_REVOKED` | 使用的 credential 已被撤銷 | 引導使用者改用帳密登入 |
| 429 | `RATE_LIMITED` | 超過速率限制 | 依 `Retry-After` 退避重試 |
| 500 | `INTERNAL_ERROR` | 伺服器內部錯誤 | 重試 / 回報平台 |

> 分流原則：`400` = 請求格式錯誤；`422` = 請求格式正確但 WebAuthn 語意驗證未過；`403` = 租戶 / origin / RP ID 授權問題。

各錯誤碼的維運排查對照見 [`maintenance-guide.md`](maintenance-guide.md) 第 6 節。

---

## 6. 責任邊界：`externalUserId` 與 IDOR 防護（最重要，務必落實）

這是整份串接手冊**最關鍵**的一節。不遵守會直接導致帳號接管等級的漏洞。

### 6.1 規則

`X-API-Key` **只驗證「租戶身分」，不驗證「終端使用者身分」**。`fido-server` 是後端驗證服務、不是身分來源，它**不知道**貴公司那側「現在登入的是誰」，也無從得知某次 HTTP 呼叫是否真由 `externalUserId` 本人發起。

因此，凡帶 `externalUserId` 的端點（註冊 2.1/2.2、裝置管理 4.1/4.2、查詢 5.1/5.2），**貴公司後端必須自行確保** `externalUserId` 等於「本次 HTTP 請求所對應、已通過貴公司自家帳密系統驗證的登入使用者」。

- **正確**：`externalUserId` 從貴公司後端**自己的登入 session / 授權上下文**取得，再 server-to-server 帶入本 API。
- **絕對禁止**：把前端（瀏覽器 / App）送來的 `externalUserId` 原封不動轉呼叫本 API。

### 6.2 不遵守的後果（IDOR，越權存取他人物件）

若貴公司後端信任前端傳入的 `externalUserId`，任一已登入使用者 A 只要把該值竄改為使用者 B 的 ID，即可：

- **列出 / 撤銷使用者 B 的裝置**（4.1/4.2）：把 B 的 FIDO 裝置全部撤銷，形同對 B 阻斷 FIDO 登入服務。
- **查閱 B 的綁定狀態與稽核歷程**（5.1/5.2）：洩漏 B 的隱私與操作紀錄。
- **替使用者 B 加掛一台 A 自己掌控的 FIDO 裝置**（2.1/2.2）：之後 A 即可用該裝置以 B 的身分通過 FIDO 登入，**等同帳號接管**。

### 6.3 參考範例怎麼做的

`shopping-site-reference` 的 `ShopSessionService.resolveExternalUserId(session, claimed)` 示範了正確模式：一律以後端登入 session 為準；若前端夾帶的值與 session 不符，直接回 `403 EXTERNAL_USER_ID_MISMATCH`（這是**參考範例自己**的錯誤碼，不是 `fido-server` 的錯誤碼）。請照抄此把關模式。

> 提醒：由於伺服器對「查無 / 不屬於該使用者」一律回 200（防列舉，見第 8 節），伺服器回 200 **不代表**它幫你做了終端使用者授權把關——授權完全是貴公司後端的責任。

---

## 7. 原生 App 情境的 opt-in 綁定申請流程

預設情境（Web 瀏覽器存取購物網站）**不需要任何額外綁定手續**：租戶 onboarding 時設好 `expected_origin`（= `https://<網域>`）即可使用。

若貴公司有自己的**原生 Android App**、且希望使用者能在 App 內（非瀏覽器）直接觸發 FIDO 登入，則須額外完成 Digital Asset Links 綁定（每租戶 opt-in）：

### 7.1 為什麼需要

原生 App 呼叫 Credential Manager 時，系統給的 origin 是 `android:apk-key-hash:<H>`（由 App 簽章憑證推導），而非 Web origin。伺服器必須確認「這支 App 有權代表貴公司網域」才會放行，這靠兩件事互補：App 端的 Digital Asset Links 宣告 + 伺服器端的租戶 App 授權清單把關。

### 7.2 申請步驟

**(A) 在自己網域放 `assetlinks.json`**

固定路徑：`https://<rpId>/.well-known/assetlinks.json`，以 `Content-Type: application/json`、HTTPS、可公開讀取提供。內容：

```json
[
  {
    "relation": ["delegate_permission/common.get_login_creds"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.shop.example",
      "sha256_cert_fingerprints": [
        "AB:CD:EF:...:12:34"
      ]
    }
  }
]
```

- `sha256_cert_fingerprints`：App **簽章憑證**的 SHA-256 指紋（冒號分隔大寫 hex）。若採 Google Play App Signing，須填 **Google 重新簽章後**的憑證指紋（可自 Play Console 取得）。測試簽章與正式簽章不同，要各列一筆。

**(B) 把 App 簽章指紋交給平台營運方登錄**

把 `package_name` + `sha256_cert_fingerprints` 提供給平台營運方，由營運方換算成 `android:apk-key-hash:<base64url>` 形式的 app origin，寫入該租戶的 `tenant_app_bindings` 一列。

> v1 沒有自助管理端點（origin 綁定決策 OB6：採人工 onboarding）。此清單是伺服器把 app origin 納入租戶允許清單的權威來源。純瀏覽器租戶不需要此表任何列。支援一租戶多支 App / 多組簽章（正式 + 測試）。

### 7.3 上線注意

原生 App 情境的真實 Digital Asset Links 行為與各家 OEM 相容性，v1 僅在 Pixel 9 上驗證過，其他機型未全面覆蓋（見技術限制手冊第 6 節）。opt-in 設計讓「某租戶開通 App 登入」與「平台整體上線」脫鉤——未開通前不影響任何人。

---

## 8. 防帳號列舉行為（呼叫端需知）

為避免以 HTTP 狀態碼探測「某使用者 / 某裝置是否存在」，本 API 全站一致採防列舉風格：

- 登入 options（3.1）：`externalUserId` 查無 active credential（或使用者不存在）時，仍回 200 且 `allowCredentials` 為空陣列（讓 ceremony 自然失敗），不回 404。
- 裝置列表（4.1）：使用者不存在時回 200 + `devices: []`，不回 404。
- 裝置撤銷（4.2）：不論 `deviceId` 查無、不屬於該使用者、或已撤銷，一律回 200（冪等 no-op），不回 404。
- 綁定狀態（5.1）：使用者不存在時回 200 + `enrolled=false, activeDeviceCount=0, canUseFido=false`。

因此貴公司前端應以回應 body 內容（如 `enrolled`、`devices` 陣列長度）判斷狀態，而非依賴 HTTP 狀態碼。**再次強調**：伺服器回 200 不代表它做了終端使用者授權，那是貴公司後端的責任（第 6 節）。

---

## 9. 端點請求 / 回應範例

> 以下範例的欄位定義以 [`../api-contract.md`](../api-contract.md) 為權威。

### 9.1 產生註冊 challenge — `POST /api/v1/registration/options`

Request：
```json
{ "externalUserId": "u-10023", "displayName": "王小明", "deviceLabel": "我的 Pixel" }
```
Response 200：
```json
{
  "ceremonyId": "reg_9c2f...",
  "publicKey": {
    "rp":   { "id": "shop.example.com", "name": "Example Shop" },
    "user": { "id": "<base64url user_handle>", "name": "user@example", "displayName": "王小明" },
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

### 9.2 提交註冊結果 — `POST /api/v1/registration/result`

Request：
```json
{
  "ceremonyId": "reg_9c2f...",
  "externalUserId": "u-10023",
  "credential": {
    "id": "<base64url>",
    "rawId": "<base64url>",
    "type": "public-key",
    "response": {
      "clientDataJSON": "<base64url>",
      "attestationObject": "<base64url>",
      "transports": ["internal"]
    }
  },
  "deviceLabel": "我的 Pixel"
}
```
Response 201：
```json
{
  "credentialId": "<base64url>",
  "deviceId": "dev_5a1b...",
  "device": {
    "deviceName": "我的 Pixel",
    "aaguid": "<uuid>",
    "securityLevel": "STRONG_BOX",
    "createdAt": "2026-07-21T08:00:03Z"
  },
  "signCount": 0
}
```

### 9.3 產生登入 challenge — `POST /api/v1/authentication/options`

Request（可省略 `externalUserId` 走 usernameless）：
```json
{ "externalUserId": "u-10023" }
```
Response 200：
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

### 9.4 提交登入結果 — `POST /api/v1/authentication/result`

Request：
```json
{
  "ceremonyId": "auth_3d7e...",
  "credential": {
    "id": "<base64url>",
    "rawId": "<base64url>",
    "type": "public-key",
    "response": {
      "clientDataJSON": "<base64url>",
      "authenticatorData": "<base64url>",
      "signature": "<base64url>",
      "userHandle": "<base64url>"
    }
  }
}
```
Response 200：
```json
{
  "verified": true,
  "externalUserId": "u-10023",
  "credentialId": "<base64url>",
  "deviceId": "dev_5a1b...",
  "session": { "token": "<JWT>", "tokenType": "Bearer", "expiresIn": 120 }
}
```
> 再次提醒：不要只看 `verified: true`。必須依第 4 節驗過 `session.token`（JWT）才建立自家登入 session。

### 9.5 列出裝置 — `GET /api/v1/users/{externalUserId}/devices`

Query：`status`（`ACTIVE`（預設）/`REVOKED`/`ALL`）、`limit`（預設 50，最大 100）、`cursor`。

Response 200：
```json
{
  "externalUserId": "u-10023",
  "devices": [
    {
      "deviceId": "dev_5a1b...",
      "deviceName": "我的 Pixel",
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

### 9.6 撤銷裝置 — `DELETE /api/v1/users/{externalUserId}/devices/{deviceId}`

Response 200：
```json
{ "deviceId": "dev_5a1b...", "status": "REVOKED", "revokedAt": "2026-07-21T10:00:00Z" }
```
> 撤銷採軟刪除（不實體刪列，保留 1 年稽核）。允許撤銷到 0 台（使用者回退純帳密登入），系統不阻擋。

### 9.7 查詢綁定狀態 — `GET /api/v1/users/{externalUserId}/fido-status`

Response 200：
```json
{ "externalUserId": "u-10023", "enrolled": true, "activeDeviceCount": 2, "canUseFido": true }
```

### 9.8 JWKS — `GET /api/v1/.well-known/jwks.json`（公開）

Response 200：
```json
{ "keys": [ { "kty": "EC", "crv": "P-256", "kid": "2026-fido-1", "x": "...", "y": "...", "use": "sig", "alg": "ES256" } ] }
```

---

## 10. 串接檢查清單

- [ ] 所有 API 呼叫都由後端發出，前端拿不到 API Key
- [ ] `externalUserId` 一律取自後端登入 session，未信任前端夾帶值（第 6 節）
- [ ] 登入後以 JWKS 公鑰驗證 JWT 簽章 + iss + aud + exp + jti 一次性（第 4 節），未只看 `verified`
- [ ] challenge 逾時（`CHALLENGE_EXPIRED`）時前端自動重走 options
- [ ] 對 `429 RATE_LIMITED` 做退避重試
- [ ] 前端以回應 body 判斷狀態，而非 HTTP 狀態碼（防列舉，第 8 節）
- [ ] 若走原生 App 情境，已完成 `assetlinks.json` + `tenant_app_bindings` 登錄（第 7 節）
