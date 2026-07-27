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
- 公開端點例外：`GET /api/v1/.well-known/jwks.json` 不需 API Key。`/actuator/*`（health/info/metrics/prometheus）**v1.0.0 起已移到獨立管理端口 `8444`**，不在對外的 `8443` 上，貴公司串接程式碼不需要也不應該呼叫它；詳見環境建置手冊 §2.3、維護手冊第 7 節。

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
| 3.4.A | POST | `/api/v1/authentication/cross-device/sessions` | 建立跨裝置 QR 登入 session（第 11 節） |
| 3.4.D | GET | `/api/v1/authentication/cross-device/sessions/{xdevId}/status` | 桌機輪詢跨裝置登入狀態 / 取 JWT（第 11 節） |
| 4.1 | GET | `/api/v1/users/{externalUserId}/devices` | 列出使用者已註冊裝置 |
| 4.2 | DELETE | `/api/v1/users/{externalUserId}/devices/{deviceId}` | 撤銷裝置（軟刪除） |
| 5.1 | GET | `/api/v1/users/{externalUserId}/fido-status` | 查詢使用者 FIDO 綁定狀態 |
| 5.2 | GET | `/api/v1/users/{externalUserId}/audit-events` | 稽核事件查詢（客服用，v1 可延後） |

> **關於「#」欄編號**：此欄是**合約文件 [`../api-contract.md`](../api-contract.md) 的章節編號**（方便你回查權威定義），**不是**本手冊自己的章節編號（本手冊第 3.1/3.2/3.3 節是「流程」小節，與此欄的 2.1/3.1 等無對應關係）。查權威欄位定義時請以此欄編號到 api-contract.md 對照。
>
> **關於跨裝置 QR 登入端點**：上表**刻意只列出貴公司後端會呼叫的兩個**（`3.4.A` 建立 session、`3.4.D` 桌機輪詢）。跨裝置流程另有三個端點（`3.4.B` claim、`3.4.C` result、`3.4.E` deny）由**平台提供的手機 App 直連** fido-server、不帶 `X-API-Key`、貴公司後端**不經手**，故不列在貴公司要串接的清單裡（這**不是**遺漏）。完整五端點與呼叫方拆分見第 11 節與 api-contract.md §3.4。

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
| `amr` | 同裝置登入為 `["fido","hwk"]`；**跨裝置 QR 登入（第 11 節）額外帶 `"xdev"`，即 `["fido","hwk","xdev"]`**。貴公司後端**必須**檢查此陣列是否含 `"xdev"`，據以對敏感操作要求 step-up（見第 11 節） |
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

**關於時鐘偏移（clock skew）**：

- JWT 只有 **120 秒**效期，`iat`/`exp` 的比對對兩端時鐘一致性敏感。**務必讓貴公司後端主機與 fido-server 主機都以 NTP 同步時鐘**——這是最重要的一步，遠比調容忍度重要。
- 參考範例 `FidoSessionJwtValidator` 使用 JJWT 預設值（**容忍度 = 0 秒**），未特意放寬。若貴公司環境時鐘可能有數秒誤差，可在驗證器設一個**小幅**容忍度（JJWT 的 `Jwts.parser().clockSkewSeconds(n)`），但**務必遠小於 120 秒**（例如 ≤5 秒）——設太大等於變相延長 token 有效期、削弱短效設計的防重放價值。
- 若大量出現「JWT 剛簽發卻被判過期 / 尚未生效」，優先懷疑兩端時鐘未同步，而非調大容忍度。

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

### 6.4 `externalUserId` 的格式與長度

`externalUserId` 是**貴公司自訂的、代表某位使用者的識別字串**，伺服器只把它當不透明鍵值儲存與比對，不解讀其語意。約束如下（已對照原始碼與 DB schema）：

- **必填、不可為空白**（伺服器端 `@NotBlank`；空白會回 `400 VALIDATION_ERROR`）。
- **最大長度 255 字元**（落庫欄位 `fido_user_ref.external_user_id` 為 `NVARCHAR(255)`；為 Unicode 欄位，非 ASCII 字元亦可，但長度以此為上限）。
- **字元集無額外白名單限制**（除了非空白）。伺服器不強制特定格式。
- **在同一租戶內，同一個 `externalUserId` 唯一對應一位使用者**（`UNIQUE(tenant_id, external_user_id)`）；跨租戶各自獨立、互不影響。
- **建議**：使用貴公司內部**穩定、不會變動、且非個資**的識別（例如內部 user id 而非 email / 手機號）。此值一旦與 FIDO 憑證綁定就不宜變動；若使用個資當識別，該值會出現在 session JWT 的 `sub` claim，請自行評估隱私影響。

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

把 `package_name` + `sha256_cert_fingerprints` 提供給平台營運方，由營運方透過本機 admin CLI 的 `add-app-binding` 指令登錄（CLI 自動換算成 `android:apk-key-hash:<base64url>` 形式的 app origin，寫入該租戶的 `tenant_app_bindings` 一列；指令用法見 [`environment-setup-guide.md`](environment-setup-guide.md) 第 6.3 節）。

> **指紋格式（與 `assetlinks.json` 對齊，不需自行換算）**：`add-app-binding` 的 `--fido.admin.app.sha256-fingerprint` 參數**直接接受你放進 `assetlinks.json` 的那串冒號分隔大寫 hex**（如 `AB:CD:EF:...:12:34`，冒號與空白會被忽略），**也**接受不含冒號的 64 字元 hex 或 base64。CLI 內部才換算成 `android:apk-key-hash:<base64url>` 落庫——這個 `apk-key-hash` 形式是**伺服器內部比對用**的，你**不需要**自己算，交給平台營運方原樣的指紋即可。也就是說：`assetlinks.json` 裡填什麼指紋，交給平台登錄的就是同一串，兩邊天生對齊。
>
> v1 沒有自助管理端點（origin 綁定決策 OB6：採人工 onboarding，由平台維運方在主機上執行 CLI）。此清單是伺服器把 app origin 納入租戶允許清單的權威來源。純瀏覽器租戶不需要此表任何列。支援一租戶多支 App / 多組簽章（正式 + 測試）——每組指紋各執行一次 `add-app-binding`。

**(C) 確認綁定是否成功**

v1 沒有「查詢我的 App 綁定」自助端點，確認方式有二：

1. **登錄端回報**：平台營運方執行 `add-app-binding` 成功時，CLI 會印出 `App 授權新增成功` 區塊（含 `rp_id`/`package_name`/換算後的 `apk_key_hash_origin`），並寫一筆 `audit_log`（`event_type=TENANT_APP_BINDING_ADDED`）。可請營運方回傳此輸出作為完成憑證。
2. **端對端冒煙測試（最可靠）**：在**已安裝正式簽章 App 的實機**上，於 App 內觸發一次 FIDO 註冊或登入 ceremony：
   - 若能正常進入生物辨識、且伺服器不回 `403 ORIGIN_NOT_ALLOWED` → 綁定生效。
   - 若回 `403 ORIGIN_NOT_ALLOWED` → 綁定未生效或指紋不符，逐項檢查：(a) 交付登錄的指紋是否為該 App **實際簽章**的指紋（若用 Google Play App Signing，須用 **Google 重新簽章後**的指紋，而非上傳金鑰指紋）；(b) `assetlinks.json` 是否已就位（見下方 7.3）；(c) `package_name` 是否一致。

### 7.3 上線注意

- **`assetlinks.json` 生效延遲 / 快取**：Android 系統（Credential Manager / App Links 驗證）會在 App 安裝或更新時抓取並驗證 `https://<rpId>/.well-known/assetlinks.json`，且**可能快取一段時間**。剛部署或剛修改 `assetlinks.json` 時，裝置端不一定立即看到最新內容。實務建議：先確保檔案已正確就位（正確路徑、`Content-Type: application/json`、HTTPS 可公開讀取、內容與 App 簽章一致）**再**發佈 App 或請使用者更新；測試時若剛改過檔案，可在測試機重裝 App 以強制重新驗證。**注意分工**：`assetlinks.json` 是**客戶端（OS）驗證 App↔網域關係**用的；伺服器端把 app origin 納入允許清單靠的是 `tenant_app_bindings`（`add-app-binding` 寫入，**立即生效、無快取**）。兩者都要到位，App 內 FIDO 才會通。
- **OEM 相容性**：原生 App 情境的真實 Digital Asset Links 行為與各家 OEM 相容性，v1 僅在 Pixel 9 上驗證過，其他機型未全面覆蓋（見技術限制手冊第 6 節）。
- opt-in 設計讓「某租戶開通 App 登入」與「平台整體上線」脫鉤——未開通前不影響任何人。

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
> 輪替後此清單會同時含 `ACTIVE` 與 `RETIRED` 兩把公鑰（多一個 `kid`），供過渡期驗簽；驗證時依 JWT header 的 `kid` 選對應公鑰即可。

### 9.9 稽核事件查詢 — `GET /api/v1/users/{externalUserId}/audit-events`（客服用，選配）

> **實作狀態**：合約有定義（api-contract.md §5.2 / D11），但屬**第一版可延後**的選配端點，貴公司取得的版本**不保證已實作**；導入前請向平台營運方確認。用途是客服在「帳密救援為主、客服人工為後盾」時查閱某使用者的 FIDO 操作歷程。`{externalUserId}` 同樣**必須取自後端登入 session**（第 6 節）。

Query：`from`、`to`（ISO8601）、`type`（事件類型過濾）、`limit`（預設 50，最大 100）、`cursor`。

Response 200：
```json
{
  "events": [
    { "eventId": "ev_...", "type": "AUTH_SUCCESS", "deviceId": "dev_5a1b...", "at": "2026-07-21T09:12:44Z", "detail": {} },
    { "eventId": "ev_...", "type": "AUTO_REVOKE_COUNTER_REGRESSION", "deviceId": "dev_5a1b...", "at": "2026-07-20T22:01:10Z", "detail": {} }
  ],
  "nextCursor": null
}
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
- [ ] 若走桌機 QR 跨裝置登入，已對 `amr` 含 `"xdev"` 的 session 標記為較弱來源、敏感操作要求 step-up（第 11 節）

---

## 11. 桌機 QR 跨裝置登入串接（情境三）

> 本節對應合約 [`../api-contract.md`](../api-contract.md) §3.4，以及能力/限制說明 [`technical-limitations.md`](technical-limitations.md) 第 2.2 節。**實作狀態**：架構已拍板、規格已定，但功能**尚在實作中**，導入前請向平台營運方確認貴公司取得的版本是否已提供。

讓桌機使用者用手機掃 QR 完成 FIDO 登入。桌機端只顯示 QR 與輪詢結果，真正的簽章由手機 App 用其既有硬體憑證完成。

### 11.1 呼叫方拆分（重要）

跨裝置流程有五個 fido-server 端點，分兩類呼叫方：

| 端點 | 呼叫方 | 認證 | 貴公司要做的事 |
|---|---|---|---|
| A `POST .../cross-device/sessions` | **貴公司後端** | `X-API-Key` + `desktopClientIp` | 代桌機建立 session，取回 `xdevId`/`qrUrl`/`verificationCode` |
| B `POST .../{xdevId}/claim` | **手機 App 直連** | `xdevId`（不帶 API Key） | **無**——手機 App（平台提供）直接打，貴公司不經手 |
| C `POST .../{xdevId}/result` | **手機 App 直連** | `xdevId`（不帶 API Key） | **無**——同上 |
| D `GET .../{xdevId}/status` | **貴公司後端** | `X-API-Key` + `desktopClientIp` | 代桌機輪詢；`CONFIRMED` 時取回 session JWT |
| E `POST .../{xdevId}/deny` | **手機 App 直連** | `xdevId`（不帶 API Key） | **無**——使用者在手機取消 / 本機無對應憑證時，手機 App 直接打，貴公司不經手 |

貴公司只串接 A 與 D；B/C/E 是手機 App 與 fido-server 之間的事，貴公司後端不參與。手機端呼叫 E 主動放棄後，貴公司後端在端點 D 會輪詢到 `DENIED`（見 11.2 步驟 3）。

### 11.2 串接流程

1. 桌機登入頁按「用手機掃碼登入」→ 貴公司後端呼叫 **A**，帶 `desktopClientIp`（= 桌機瀏覽器真實 client IP，從貴公司自己請求的 `remoteAddr`/`X-Forwarded-For` 取得後轉發；伺服器看到的直接來源是貴公司後端、非桌機）。取回 `xdevId`/`qrUrl`/`verificationCode`/`expiresIn`（120 秒）。

   > **多層反向代理 / CDN 下如何取 `desktopClientIp`**：`desktopClientIp` 只用於 proximity 稽核，而 proximity 是**只警示、不阻擋**（見 11.4）——因此取值**求「盡量接近真實桌機出口 IP」即可，取錯不會擋登入、也不會造成錯誤授權**，只影響稽核訊號品質。實務建議：
   > - 若貴公司前方有可信的反向代理 / 負載平衡器 / CDN，`X-Forwarded-For` 會是逗號分隔的 IP 鏈（`client, proxy1, proxy2...`）。**取你信任的最外層代理所附加的那個 client IP**（通常是**最左側**、但前提是你確認整條鏈上的中間節點都可信、不會被使用者偽造 header）；不可信任的環境下應以「你自己那層可信代理實際看到的 remote address」為準，而非盲信最左側。
   > - 用貴公司 Web 框架已解析好的 client IP（如 Spring 的 `ForwardedHeaderFilter` / `RemoteIpValve` 設定好 trusted proxies 後的 `request.getRemoteAddr()`）最穩妥，避免自己手解 `X-Forwarded-For`。
   > - 取不到精確值時，帶上你能取得的最佳近似值即可；切勿因此阻斷流程。
2. 桌機把 **`qrUrl`** 顯示成 QR，並顯示 `verificationCode` 供使用者與手機畫面比對。

   > **釐清 `xdevId` 與 `qrUrl` 的關係（避免誤解「不要交給前端」與「顯示成 QR」的表面矛盾）**：
   >
   > - `qrUrl` 的形式是 `https://<fido-app-link-host>/x/<xdevId>`，**其中確實內嵌了 `xdevId`**——`xdevId` 是 QR 唯一承載的內容，QR 本來就是要給使用者的手機掃描的，所以 `xdevId` 出現在 QR 圖片裡是**設計如此、無法也不需要隱藏**。你要交給前端渲染成 QR 圖片的欄位就是 `qrUrl`（等同 `xdevId`）。建議由**後端**把 `qrUrl` 直接算成 QR 圖片（或 data URL）回給前端 `<img>`，讓原始字串不成為前端 JS 的一級變數，但安全性**並不依賴**隱藏這個值。
   > - 「**不要把 `xdevId` 交給前端 JS**」這條規則管的**不是** QR 渲染，而是**輪詢/領取結果的通道**：決定「哪個桌機瀏覽器最後能領到 CONFIRMED 的 session JWT」的綁定，**必須由貴公司後端在伺服器端維護**（例如發一個 httpOnly cookie，把「本次桌機瀏覽器 session」對映到 `xdevId`），**你的輪詢端點只認這個 cookie，絕不接受由前端 JS 用參數帶進來的 `xdevId`**。
   > - 為什麼：`xdevId` 印在 QR 上，任何人只要拍到 QR 或旁觀到就能得知它。若你的輪詢/領取是「前端拿著 `xdevId` 來換結果」，那麼拍到 QR 的第三方就能冒領受害者的登入結果（把受害者剛完成的 FIDO 登入 session 建立到攻擊者的瀏覽器）。改以伺服器端 httpOnly cookie 綁定「發起該 session 的那一個瀏覽器」，就能確保 CONFIRMED 的 JWT 只交回原本那台桌機。
   > - 補充第二層保護：即使第三方直接拿 `xdevId` 去打 **fido-server 的端點 D**，該端點要求貴公司後端的 `X-API-Key`（前端與第三方都沒有），也拿不到 JWT。
   > - `shopping-site-reference` 參考範例即是此模式：後端設一個 httpOnly `XDEV_POLL` cookie 綁定瀏覽器與 `xdevId`，poll 端點只認 cookie、不接受查詢參數帶 `xdevId`。請照抄此模式。
3. 桌機每 2–3 秒請貴公司後端輪詢 → 後端帶 `desktopClientIp` 呼叫 **D**：
   - `PENDING`/`SCANNED` → 尚未完成，繼續輪詢。
   - `CONFIRMED` → 回應含 `session.token`（JWT）與 `warnings.proximityMismatch`。**此 JWT 只能領一次**（領後 session 轉 `CONSUMED`）。
   - `DENIED`（使用者在手機取消 / 手機上無對應憑證）→ 停止輪詢、顯示對應訊息。
     - **限制（誠實揭露）**：端點 D 回應的 `DENIED` **不會告訴桌機「是哪一種原因」**（「使用者主動取消」vs「本機無對應憑證」）。細部原因（`USER_CANCELLED`/`NO_CREDENTIAL`/`UNSPECIFIED`）只寫入 fido-server 的 `audit_log.detail.denyReason` 供事後稽核，**不回傳到桌機**（避免向桌機端洩漏「該手機是否已註冊本站憑證」這類可被探測的資訊）。因此桌機端只能顯示**一則通用訊息**（例如「登入未完成，請重新產生 QR 或改用帳密登入」），無法據此對兩種情況給不同引導文案。若貴公司有此需求，請提報平台營運方評估（見下方待辦/未來擴充）。
   - 逾時 → `XDEV_SESSION_EXPIRED` / `EXPIRED`，顯示「QR 已過期，請重新產生」。
4. 拿到 `CONFIRMED` + JWT 後，**收尾與一般登入完全相同**：依第 4 節驗 JWT（JWKS 驗簽 + iss/aud/exp/jti），驗過才建立自家登入 session。**不要**只看回應的 `status`。

### 11.3 必做：對 `amr` 含 `"xdev"` 的 session 限制敏感操作（step-up）

這是跨裝置登入能安全上線的**關鍵責任**，落在貴公司後端：

- 驗過的 session JWT，若 `amr` 陣列含 `"xdev"`，代表這是經**較弱的 cross-device 路徑**取得的登入（防釣魚弱於同裝置，見技術限制手冊第 2.2 節）。
- 貴公司後端建立自家 session 時**應記錄此來源**，並在授權層對**敏感操作**（改密碼、綁定/解綁、金流交易、撤銷 FIDO 裝置、修改個資等）**要求 step-up 驗證**——例如引導使用者在同裝置重新做一次 FIDO 或帳密驗證後才放行。
- 低風險操作（瀏覽、加入購物車等）可正常放行。
- **平台不會、也無法代貴公司強制這件事**：fido-server 只誠實在 `amr` 標記登入路徑強度，它不是身分來源、也不知道貴公司「哪個動作算敏感」（與第 6 節 `externalUserId` 責任邊界同理）。若貴公司後端忽略 `"xdev"` 標記直接放行敏感操作，等於自行放棄了限縮範圍這層保護。
- `shopping-site-reference` 參考範例會示範此 step-up 判斷邏輯，請照抄其模式。

### 11.4 proximity 只警示、不阻擋

D 回應的 `warnings.proximityMismatch=true` 代表「桌機與手機的網路出口 IP 不一致」（可能是遠端中繼，也可能只是手機走行動網路、桌機走 Wi-Fi 的正常誤判）。**系統不會因此擋下登入**（平台決策為警示制）。貴公司後端可把此訊號納入自己的風控/紀錄，但不應假設「沒有 mismatch = 安全」。維運層的追蹤見維護手冊第 11 節。
