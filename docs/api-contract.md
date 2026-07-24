# FIDO 驗證伺服器 — REST API 合約

- 版本：v1（草案，待人工複核）
- 最後更新：2026-07-24
- 適用架構情境：A（標準 WebAuthn，同裝置）為主；另含情境三（跨裝置 QR transaction confirmation，見 §3.4）
- 對應共識文件：`d:\fido\CLAUDE.md`

> 本文件是 **FIDO 驗證伺服器對外的後端 REST API 合約**。所有端點的呼叫方（除 JWKS、health、以及 §3.4 兩個手機直連端點外）都是 **購物網站的後端**（租戶 backend），以 server-to-server 方式呼叫，並以 API Key 表明租戶身分。同裝置情境（A）下，FIDO 驗證 APP（Android CredentialProviderService）不直接呼叫本 API；它透過同裝置的 WebAuthn / Credential Manager 與購物網站前端互動，產生的 attestation / assertion 由購物網站前端交回購物網站後端，再由後端轉呼叫本 API 驗證。
>
> **例外（情境三，見 §3.4）**：跨裝置 QR 登入中，手機 App **會直連**本 API 的 claim / result 兩個端點，且**不帶 X-API-Key**，改以不透明 `xdevId` capability 認證（租戶由 `xdevId` 反查）。這是刻意的新增呼叫方類別，見 §1.2 呼叫方類別說明。
>
> 凡標記 **【本文件補充決策】** 者，為 CLAUDE.md 未覆蓋、由系統分析師於本文件先行決定的細節，需人工複核後回填 CLAUDE.md。全部清單見文末附錄 A。

---

## 目錄

1. [API 總覽與慣例](#1-api-總覽與慣例)
   - 1.1 Base path 與版本策略
   - 1.2 租戶識別與認證（API Key）
     - 1.2.1 終端使用者身分驗證是呼叫端的責任（防 IDOR）
     - 1.2.2 呼叫方類別：手機 App 直連、以 `xdevId` capability 認證（情境三）
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
   - 3.4 跨裝置 QR 登入（情境三）
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

#### 1.2.1 終端使用者身分驗證是呼叫端的責任（防 IDOR）

- **【本文件補充決策 D15】** `X-API-Key` **只驗證「租戶身分」，不驗證「終端使用者身分」**。FIDO 伺服器是多租戶後端驗證服務、非身分來源（對齊 CLAUDE.md 定位），它**不知道**購物網站那側「目前登入的是誰」，也**無從**得知某次 HTTP 呼叫是否真由 `externalUserId` 本人發起。
- 因此，**凡請求路徑或參數帶 `externalUserId` 的端點**（註冊 §2.1 / §2.2、裝置管理 §4.1 / §4.2、查詢 §5.1 / §5.2），**呼叫端（購物網站後端）必須自行確保**代入的 `externalUserId` 等於「本次 HTTP 請求所對應、已通過購物網站自家帳密系統驗證的登入使用者」。伺服器**不會、也無法代為檢查**此事。
- 正確做法：`externalUserId` 應由購物網站後端從**自己的登入 session / 授權上下文**取得，再以 server-to-server 帶入本 API；**絕不可**直接把前端（瀏覽器 / App）送來的 `externalUserId` 原封不動轉呼叫本 API。
- **未落實此把關的具體風險 —— IDOR（Insecure Direct Object Reference，越權存取他人物件）**：若購物網站後端信任前端傳入的 `externalUserId`，任一已登入使用者 A 只要竄改該值為使用者 B 的 ID，即可：
  - **列出 / 撤銷使用者 B 的裝置**（§4.1 / §4.2）—— A 可把 B 的 FIDO 裝置全部撤銷，形同對 B 的阻斷服務；
  - **查閱 B 的綁定狀態與稽核歷程**（§5.1 / §5.2）—— 洩漏 B 的隱私與操作紀錄；
  - **替使用者 B 加掛一台 A 自己掌控的 FIDO 裝置**（§2.1 / §2.2）—— 之後 A 即可用該裝置以 B 的身分通過 FIDO 登入，**等同帳號接管**。

> 端點 3.3（JWKS）與 health 端點為**公開端點**，不需 API Key。

#### 1.2.2 呼叫方類別：手機 App 直連、以 `xdevId` capability 認證（情境三）

- **【本文件補充決策 D16】** 跨裝置 QR 登入（§3.4）打破「所有端點呼叫方皆為購物網站後端、以 X-API-Key 表明租戶」的前提。§3.4 的三個端點（B `.../claim`、C `.../result`、E `.../deny`）由**手機 App 直連** fido-server，**不帶 X-API-Key**，改以路徑上的不透明高熵一次性 `xdevId` 作 **capability（bearer）認證**：伺服器由 `xdevId` 反查 `cross_device_sessions` → `tenant_id` 決定租戶。
- 理由：多租戶下手機 App 是單一營運方 App、服務多個租戶，掃 QR 當下無從得知該打哪個租戶的 API Key，也不應持有任何租戶的 API Key；`xdevId` 由伺服器在 §3.4 端點 A 建立 session 時產生、只承載於 QR、高熵不可猜、一次性、綁定單一 session，最適合作能力憑證。
- §3.4 的另兩個端點（A `POST .../sessions`、D `GET .../sessions/{xdevId}/status`）**仍由購物網站後端**呼叫，**仍帶 X-API-Key**（與其他所有後端端點一致），另加 `desktopClientIp` 欄位轉發桌機瀏覽器的真實 client IP（proximity 稽核用，見 §3.4）。（端點 E `.../deny` 為手機端主動放棄訊號，屬手機直連類別，見上一點。）
- **防列舉**：`xdevId` 是高熵一次性 capability 值、**非**使用者/裝置識別，不受 D7 防帳號列舉策略約束（D7 針對的是「使用者/裝置是否存在」的探測，`xdevId` 不洩漏任何此類資訊）。故 `xdevId` 查無時回 `404 XDEV_SESSION_NOT_FOUND` 語意正確、無列舉風險。

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
  | `amr` | string[] | 認證方式參照（Authentication Method Reference）。同裝置情境（A）為 `["fido","hwk"]`（hardware key）。**跨裝置 QR 登入（情境三，§3.4）額外帶 `"xdev"`，即 `["fido","hwk","xdev"]`**（見下方 `amr` 與 cross-device 標記說明 **【D17】**）。 |
  | `auth_time` | number | 驗證完成的 epoch 秒 |
  | `iat` / `exp` | number | 簽發 / 到期，`exp - iat = 120` |
  | `jti` | string | 一次性 token ID；購物網站**應**做一次性消費防重放 |

- 驗證方式：購物網站以端點 3.3 的 JWKS 公鑰驗簽，並自行校驗 `iss` / `aud`（= 自己網域）/ `exp` / `jti` 未用過。

**`amr` 與 cross-device 登入標記（step-up 依據）【本文件補充決策 D17，本次新增】**：

- **背景**：cross-device QR 登入（§3.4）在防釣魚上本質弱於同裝置 WebAuthn（見 `docs/decisions/qr-cross-device-login-design.md` 第 5 節），故 CLAUDE.md 決策「跨裝置 QR 登入 — 使用範圍限制（S7）」限縮其可用範圍：以此路徑取得的 session 只應用於低風險動作，敏感動作（改密碼、金流、裝置撤銷/管理）仍須同裝置 step-up。
- **標記機制**：fido-server 於 §3.4 端點 C 簽發的 session JWT，其 `amr` **在 `["fido","hwk"]` 之外多帶一個 `"xdev"` 值**。同裝置登入（§3.2）不帶 `"xdev"`。這是**購物網站後端（或任何驗證此 JWT 的下游）辨識「本次 session 是否經 cross-device 較弱路徑取得」的唯一權威依據**。
- **下游應如何用**：驗過 JWT（§1.3 / 採用廠商手冊第 4 節）後，若 `amr` 陣列含 `"xdev"`，下游在自己的授權層對敏感操作**必須要求 step-up 驗證**（例如引導使用者在同裝置重新做一次 FIDO 或帳密驗證），**不可**憑此 session 直接放行敏感操作。低風險操作（瀏覽、加入購物車、一般結帳前流程等）可正常放行。
- **責任邊界**：fido-server **只誠實標記登入路徑強度、不強制 S7**——它非身分來源、也看不到下游「哪個動作算敏感」，enforcement 落在下游後端（與 §1.2.1 / D15「終端使用者授權把關由呼叫端負責」的責任邊界一致）。`shopping-site-reference` 會示範此 step-up 判斷邏輯供採用廠商參照。

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
| 404 | `XDEV_SESSION_NOT_FOUND` | 跨裝置 QR 登入 session（`xdevId`）不存在（§3.4）。`xdevId` 為高熵一次性 capability、非使用者/裝置識別，回 404 無列舉風險（見 §1.2.2 / D16） |
| 400 | `XDEV_SESSION_EXPIRED` | 跨裝置 QR 登入 session 已逾時（超過 120 秒 TTL）或已被消費（§3.4） |
| 409 | `XDEV_SESSION_INVALID_STATE` | 跨裝置 QR 登入 session 狀態機不允許此操作（例如對非 `PENDING` 的 session 再 claim、對非 `SCANNED` 的 session 提交 result、重複領取已 `CONSUMED` 的結果）（§3.4） |
| 429 | `RATE_LIMITED` | 超過速率限制 |
| 500 | `INTERNAL_ERROR` | 伺服器內部錯誤 |

> **關於 proximity（近距/出口 IP）檢查——刻意不設拒絕碼**：CLAUDE.md 決策「跨裝置 QR 登入 — proximity 政策（S2）」拍板為**只警示、不阻擋**。因此 proximity 不符**不是**一個導致 4xx 拒絕的錯誤碼；伺服器改為在 §3.4 端點 C/D 的**成功回應中夾帶一個警示欄位** `proximity`（見 §3.4 回應格式）並於 `audit_log.detail.proximityMismatch=true` 留痕，登入照常完成。（先前設計文件曾提議 `422 XDEV_PROXIMITY_MISMATCH`，已因 S2 拍板為警示制而**移除**，不列入本錯誤碼表。）

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

> **安全提醒（IDOR，見 §1.2.1 / D15）**：`externalUserId` 必須取自購物網站後端**自己的登入 session**，不可信任前端傳入值。否則使用者 A 可指定使用者 B 的 ID，替 B 加掛一台 A 自己掌控的 FIDO 裝置（§2.2 完成註冊後），之後即能以 B 的身分通過 FIDO 登入，**等同帳號接管**。

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `externalUserId` | string | 是 | 購物網站的使用者 ID（身分權威來源在購物網站）。**須為本次請求的已驗證登入使用者，見 §1.2.1 / D15。** |
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

> **安全提醒（IDOR，見 §1.2.1 / D15）**：`externalUserId` 同樣須取自後端登入 session，且應與 §2.1 為同一使用者；若信任前端傳入值，新註冊的裝置會被綁到攻擊者指定的他人帳號上（帳號接管風險）。

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `ceremonyId` | string | 是 | 2.1 回傳值 |
| `externalUserId` | string | 是 | 與 2.1 相同使用者。**須為本次請求的已驗證登入使用者，見 §1.2.1 / D15。** |
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

### 3.4 跨裝置 QR 登入（情境三）

桌機瀏覽器發起、顯示 QR，手機 App 掃碼後作漫遊確認器，用手機上既有已註冊的憑證做一次真正的 WebAuthn assertion 送回；桌機輪詢取得結果。完整設計、時序、威脅模型與殘餘風險見 `docs/decisions/qr-cross-device-login-design.md`；本節僅定合約。**安全前提（擁有者已拍板）**：此路徑防釣魚弱於同裝置（S1 殘餘風險已接受），使用範圍限縮於低風險動作（S7，經 §1.3 / D17 的 `amr` `"xdev"` 標記讓下游對敏感操作要求 step-up）；proximity 檢查**只警示不阻擋**（S2）。

**五個端點（呼叫方 / 認證方式見 §1.2.2 / D16）**：

| # | Method | Path | 呼叫方 | 認證 | 作用 |
|---|---|---|---|---|---|
| A | POST | `/api/v1/authentication/cross-device/sessions` | 購物網站後端 | `X-API-Key` + body `desktopClientIp` | 建 session，回 `xdevId`/`qrUrl`/`verificationCode`/`expiresIn` |
| B | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/claim` | **手機 App 直連** | `xdevId`（capability，**不帶 X-API-Key**） | `PENDING→SCANNED`，回權威 `rpId`/`origin`/`challenge`/`verificationCode`/`tenantDisplayName` |
| C | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/result` | **手機 App 直連** | `xdevId`（capability，**不帶 X-API-Key**） | 驗 assertion（重用既有邏輯）+ proximity 檢查（僅警示），`SCANNED→CONFIRMED` |
| D | GET | `/api/v1/authentication/cross-device/sessions/{xdevId}/status` | 購物網站後端 | `X-API-Key` + query/header `desktopClientIp` | 回狀態；`CONFIRMED` 時回 session JWT 並 `→CONSUMED` |
| E | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/deny` | **手機 App 直連** | `xdevId`（capability，**不帶 X-API-Key**） | 使用者取消 / 本機無對應 rpId 憑證，`PENDING`\|`SCANNED`→`DENIED` + audit |

challenge / xdev session TTL = **120 秒**（S6，僅此 ceremony type，見 CLAUDE.md「Challenge 時效」）。狀態機：`PENDING → SCANNED → CONFIRMED → CONSUMED`，另有 `DENIED`（使用者取消 / 本機無對應 rpId 憑證，由端點 E 觸發）、`EXPIRED`（TTL 到期，由伺服器背景清理 / 輪詢時判定）。每個轉移單向不可逆；claim 只在 `PENDING`、result 只在 `SCANNED`、status 取 JWT 只在 `CONFIRMED` 成功且立即轉 `CONSUMED`（JWT 只能被領一次）、deny 在 `PENDING` 或 `SCANNED` 皆可（見端點 E），違反狀態機 → `409 XDEV_SESSION_INVALID_STATE`。

> **DENIED 與 EXPIRED 的語意區別（為何 DENIED 需要一個主動觸發端點）**：`EXPIRED` 是被動逾時（使用者可能只是分心、離開、網路斷線，資訊量低）；`DENIED` 是使用者**看過確認畫面後主動拒絕**（尤其「不是我，取消」＝可能識破了一次中繼/釣魚嘗試，是強訊號）。在 proximity 採 warn-only（S2）、不阻擋遠端即時中繼的風險態勢下（見 CLAUDE.md 情境三決策定案「S1+S2 風險敞口」段落），使用者於確認畫面上的警覺是少數幾個補償控制之一，而端點 E 的 `DENIED` + `audit_log` 正是讓「使用者主動識破並拒絕」這個訊號被營運方觀察到的唯一途徑。若無此端點、一律走自然逾時 `EXPIRED`，這個稽核/偵測訊號會流失。此設計對應設計文件 §5.2.3 第 3 點（確認畫面「不是我」明確拒絕出口，標為【必要】）與 §4.2 App 端流程，屬**完成既有已拍板設計的落地缺口**，非新架構決策。

---

#### 3.4.A 建立 cross-device session

`POST /api/v1/authentication/cross-device/sessions`（購物網站後端，`X-API-Key`）

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `desktopClientIp` | string | 是 | 桌機瀏覽器的真實 client IP（購物網站後端從其自身請求的 `remoteAddr` / `X-Forwarded-For` 取得後轉發）。proximity 稽核基準；伺服器看到的直接來源是購物網站後端 IP、非桌機，故須由後端誠實轉發（後端本為租戶自家可信元件）。 |

**Response 200**

```json
{
  "xdevId": "<不透明高熵 base64url>",
  "qrUrl": "https://<fido-app-link-host>/x/<xdevId>",
  "verificationCode": "38-421",
  "expiresIn": 120
}
```

- `qrUrl`：桌機編碼成 QR；只承載不透明 `xdevId`（不含 rpId/challenge/使用者資訊），為已驗證的 App Link（見設計文件 3.1 與 origin-binding.md OB7）。
- `verificationCode`：確認碼，桌機 QR 頁與手機確認畫面各顯示一份供使用者比對（輔助防禦，非主力，見設計文件 5.4）。

**主要錯誤**：`401 UNAUTHENTICATED`、`403 TENANT_DISABLED`、`400 VALIDATION_ERROR`、`429 RATE_LIMITED`。

**核心表對應**：讀 `tenants`；insert `auth_challenges`（`ceremony_type='AUTHENTICATION'`、`user_ref_id=NULL`、`expires_at=now+120s`）；insert `cross_device_sessions`（`status='PENDING'`、`desktop_ip`、`verification_code`、`challenge_pk`）；`audit_log` 記 `XDEV_SESSION_CREATED`，`detail.originType='CROSS_DEVICE_QR'`。

---

#### 3.4.B claim（手機取權威 context）

`POST /api/v1/authentication/cross-device/sessions/{xdevId}/claim`（手機 App 直連，`xdevId` capability，不帶 X-API-Key）

伺服器驗 `xdevId` 存在（否則 `404 XDEV_SESSION_NOT_FOUND`）、未過期（否則 `400 XDEV_SESSION_EXPIRED`）、狀態為 `PENDING`（否則 `409 XDEV_SESSION_INVALID_STATE`），轉 `PENDING→SCANNED`，回權威 context。

**Response 200**

```json
{
  "rpId": "shop.example.com",
  "origin": "https://shop.example.com",
  "tenantDisplayName": "Example Shop",
  "challenge": "<base64url 32-byte>",
  "verificationCode": "38-421",
  "expiresAt": "2026-07-24T08:00:00Z"
}
```

- `rpId` / `origin` **由伺服器依 `xdevId → tenant` 權威給定**，App 與 QR 都不得自行宣稱（見 origin-binding.md OB7）。App 據此 rpId 查本機憑證、據 `origin` 簽 `clientDataJSON`。

**核心表對應**：讀 `cross_device_sessions` join `auth_challenges` + `tenants`；update `cross_device_sessions`（`status='SCANNED'`、`scanned_at`、`phone_ip` 可先記）；`audit_log` 記 `XDEV_CLAIMED`。

---

#### 3.4.C 提交 assertion result（含 proximity 警示）

`POST /api/v1/authentication/cross-device/sessions/{xdevId}/result`（手機 App 直連，`xdevId` capability）

**Request**：body = 標準 assertion JSON（結構同 §3.2 的 `credential.{id,rawId,type,response.{clientDataJSON,authenticatorData,signature,userHandle}}`）。

**伺服器處理**：

1. 狀態須為 `SCANNED`（否則 `409 XDEV_SESSION_INVALID_STATE`）、未過期（否則 `400 XDEV_SESSION_EXPIRED`）。
2. **assertion 密碼學驗證重用既有 `AuthenticationService.verifyResult`**（以 `xdevId` 反查其 `challenge_pk` 對應 ceremony）：`webauthn.get` 型別、challenge 比對、rpIdHash 比對、origin 允許清單比對、UV flag、公鑰驗簽、sign counter（含倒退自動撤銷）。失敗回既有的 `422 ASSERTION_INVALID` / `422 SIGN_COUNTER_REGRESSION` / `422 CREDENTIAL_REVOKED` 等（與 §3.2 一致）。
3. **proximity 檢查（只警示不阻擋，S2）**：比對 `cross_device_sessions.desktop_ip`（端點 A 轉來的桌機 IP）與本次手機直連來源 IP。**不一致不拒絕**，只在回應夾帶警示欄位並寫 `audit_log.detail.proximityMismatch=true`。
4. 通過 → `SCANNED→CONFIRMED`，簽發 session JWT（`amr` 含 `"xdev"`，見 §1.3 / D17）暫存於 session，待端點 D 領取。

**Response 200**

```json
{
  "status": "CONFIRMED",
  "proximity": { "checked": true, "mismatch": false }
}
```

- `proximity.mismatch=true` 時，手機確認畫面可據以提示使用者「登入位置與電腦端不一致」，但**登入仍然成功**（警示制）。

**核心表對應**：讀/消費 `auth_challenges`；讀/更新 `fido_credentials`（sign_count/status）、`bound_devices`（last_used_at）；update `cross_device_sessions`（`status='CONFIRMED'`、`confirmed_at`、`phone_ip`、`user_ref_id`、`credential_pk`、`issued_jti`）；`audit_log` 記 `XDEV_CONFIRMED` / 失敗原因，`detail` 含 `originType='CROSS_DEVICE_QR'`、`proximityMismatch`。

---

#### 3.4.D 桌機輪詢狀態 / 取 JWT

`GET /api/v1/authentication/cross-device/sessions/{xdevId}/status`（購物網站後端，`X-API-Key`）

購物網站後端每 2–3 秒代桌機輪詢（既有每租戶 100 TPS 已足吸收）。帶 `desktopClientIp`（同端點 A）。

**Response 200**（依狀態）

```json
{
  "status": "CONFIRMED",
  "session": { "token": "<JWT，amr 含 xdev>", "tokenType": "Bearer", "expiresIn": 120 },
  "warnings": { "proximityMismatch": false }
}
```

- `status` 為 `PENDING`/`SCANNED` → 回該狀態、無 `session`（桌機繼續輪詢）。
- `status` 為 `CONFIRMED` → **回 session JWT 並立即將 session 轉 `CONSUMED`**（JWT 只能被領一次；再次輪詢回 `409 XDEV_SESSION_INVALID_STATE` 或 `EXPIRED`）。`warnings.proximityMismatch` 反映本次 cross-device 登入的 proximity 檢查結果，供購物網站後端記錄/風控。
- `status` 為 `DENIED`（使用者取消 / 本機無憑證）→ 回 `DENIED`，桌機顯示對應訊息並停止輪詢。
- session 逾時 → `400 XDEV_SESSION_EXPIRED`（或 `status:"EXPIRED"`），桌機顯示「QR 已過期，請重新產生」。

**購物網站後端收尾（同 §3.2 信任邊界）**：**不信任任何 `status`/`confirmed` 布林**，只信 JWKS 驗過簽的 session JWT（見 §1.3）。驗過後，因 `amr` 含 `"xdev"`，建立的自家 session **應標記為 cross-device 來源**，供後續敏感操作 step-up 判斷（§1.3 / D17）。

**核心表對應**：讀 `cross_device_sessions`；`CONFIRMED` 時 update（`status='CONSUMED'`、`consumed_at`）；`audit_log` 記 `XDEV_CONSUMED`。

---

#### 3.4.E 手機主動放棄（使用者取消 / 本機無憑證）

`POST /api/v1/authentication/cross-device/sessions/{xdevId}/deny`（手機 App 直連，`xdevId` capability，不帶 X-API-Key）

手機端於下列兩種情形呼叫此端點，讓伺服器把 session 主動轉為 `DENIED` 並留下即時稽核訊號（對應設計文件 §4.2 步驟 3/4）：

1. **使用者在確認畫面按「不是我，取消」**（`reason=USER_CANCELLED`）——最具偵測價值的訊號：可能是使用者識破了一次中繼/釣魚嘗試。
2. **claim 後發現本機無該 rpId 的 active 憑證**（`reason=NO_CREDENTIAL`）——通常為良性（此裝置根本沒註冊該租戶）。

**與被動逾時（EXPIRED）的差異、以及此端點存在的理由**：見上方 §3.4 開頭「DENIED 與 EXPIRED 的語意區別」說明。

**Request**

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `reason` | string | 否 | 放棄原因，列舉值 `USER_CANCELLED` / `NO_CREDENTIAL`。省略時伺服器記為 `UNSPECIFIED`。**僅供稽核分類，不影響狀態轉移結果**（無論哪個原因都轉 `DENIED`）；伺服器不信任此值作任何授權判斷，僅原樣寫入 `audit_log.detail.denyReason`。 |

**伺服器處理**：

1. 驗 `xdevId` 存在（否則 `404 XDEV_SESSION_NOT_FOUND`）、未過期（否則 `400 XDEV_SESSION_EXPIRED`）。
2. 狀態須為 `PENDING` 或 `SCANNED` → 轉 `DENIED`，回 200。
3. **冪等**：若已是 `DENIED`，回 200（不重複寫稽核，或寫 `matched:false` 補記，dev 擇一，對呼叫端回應無差異）。
4. 若為終端成功狀態 `CONFIRMED` / `CONSUMED`（登入已完成）→ `409 XDEV_SESSION_INVALID_STATE`（不允許事後把已完成的登入翻成 DENIED）。

**Response 200**

```json
{ "status": "DENIED" }
```

**主要錯誤**：`404 XDEV_SESSION_NOT_FOUND`、`400 XDEV_SESSION_EXPIRED`、`409 XDEV_SESSION_INVALID_STATE`。（此端點不做密碼學驗證，無 `422` 類錯誤；不新增任何錯誤碼，沿用 §1.4 既有 `XDEV_*`。）

**與桌機輪詢的銜接**：端點 E 轉 `DENIED` 後，購物網站後端下次 poll（端點 D）即依既有合約（§3.4.D）取得 `status:"DENIED"` 並停止輪詢、顯示對應訊息——**端點 D 無需任何改動**（其 DENIED 回應路徑本已定義）。

**核心表對應**：讀 `cross_device_sessions`（驗 `xdevId`/狀態/時效）；update `cross_device_sessions`（`status='DENIED'`、`updated_at`）；`audit_log` 記 `XDEV_DENIED`，`detail` 含 `originType='CROSS_DEVICE_QR'`、`denyReason`（`USER_CANCELLED`/`NO_CREDENTIAL`/`UNSPECIFIED`）。

---

## 4. 裝置管理 API

> 對應 CLAUDE.md「允許使用者註冊多台裝置，提供管理介面」。這些端點由購物網站後端（使用者已帳密登入的裝置管理頁）呼叫。
>
> **安全提醒（IDOR，見 §1.2.1 / D15）**：本節兩端點的 `{externalUserId}` 皆位於 URL 路徑。購物網站後端**必須**確保該值等於本次請求的已驗證登入使用者，不可用前端直接傳入的值組 URL。否則使用者 A 只要改動路徑中的 ID，就能**列出或撤銷使用者 B 的裝置**（§4.2 撤銷 B 全部裝置形同對 B 阻斷 FIDO 登入），此為典型 IDOR 越權。注意：§4.1 / §4.2 依 D7 防列舉策略對「查無 / 不屬於該使用者」一律回 200（空清單 / 冪等 no-op），**不代表**伺服器有做終端使用者授權把關——授權完全由呼叫端負責。

### 4.1 列出使用者已註冊裝置

`GET /api/v1/users/{externalUserId}/devices`

> **IDOR 提醒**：`{externalUserId}` 須為本次請求的已驗證登入使用者，否則將洩漏他人裝置清單（見 §1.2.1 / D15）。

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

> **IDOR 提醒**：`{externalUserId}` 須為本次請求的已驗證登入使用者，否則使用者 A 可撤銷使用者 B 的裝置（見 §1.2.1 / D15）。伺服器僅驗證 `{deviceId}` 是否屬於路徑帶入的 `{externalUserId}`+租戶（不屬於則冪等 no-op），但**無法驗證該 `externalUserId` 是否即是呼叫者本人**。

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
| 3.4.A | POST | `/api/v1/authentication/cross-device/sessions` | R `tenants`；C `auth_challenges`,`cross_device_sessions`；C `audit_log` |
| 3.4.B | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/claim` | R `cross_device_sessions`,`auth_challenges`,`tenants`；U `cross_device_sessions`；C `audit_log` |
| 3.4.C | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/result` | RU `auth_challenges`,`fido_credentials`,`bound_devices`,`cross_device_sessions`；C `audit_log` |
| 3.4.D | GET | `/api/v1/authentication/cross-device/sessions/{xdevId}/status` | RU `cross_device_sessions`；C `audit_log` |
| 3.4.E | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/deny` | RU `cross_device_sessions`；C `audit_log` |
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
| D13 | `registration/result` / `authentication/result` / 跨裝置 `cross-device/*`（§3.4）於 `audit_log.detail` 記 `originType`(`WEB`/`NATIVE_APP`/`CROSS_DEVICE_QR`) | origin-binding.md OB5（WEB/NATIVE_APP）＋情境三新增 `CROSS_DEVICE_QR`；事後鑑識登入來源，用既有 JSON 欄位無 schema 變更 |
| D14 | 租戶 App 授權清單 v1 不新增 REST 端點，採人工 onboarding 寫 `tenant_app_bindings` | origin-binding.md OB6，人類已拍板；中小規模人工足夠、不影響 ceremony 驗證 |
| D15 | 明文載明「終端使用者身分驗證由呼叫端（購物網站後端）負責、伺服器只以 `X-API-Key` 驗租戶身分」；凡帶 `externalUserId` 的端點，呼叫端須確保其等於本次請求的已驗證登入使用者，否則造成 IDOR（越權列出/撤銷他人裝置、替他人加掛攻擊者裝置＝帳號接管） | 澄清既有隱含責任邊界，非新架構決策、不改端點行為；避免串接團隊忽略此步造成 IDOR。見 §1.2.1 |
| D16 | 新增「手機 App 直連、以 `xdevId` capability 認證、不帶 X-API-Key」的呼叫方類別（§3.4 端點 B/C/E）；`xdevId` 高熵一次性、非使用者/裝置識別、不受 D7 防列舉約束 | 情境三跨裝置 QR 登入（S5，擁有者已拍板）；多租戶下手機無從得知該打哪個租戶 API Key，`xdevId` 反查租戶最乾淨。見 §1.2.2 / §3.4 |
| D18 | 新增 §3.4 端點 E `POST .../deny`（手機直連、`xdevId` capability），把 `PENDING`/`SCANNED` 轉 `DENIED` + 寫 `audit_log`（`XDEV_DENIED`，`detail.denyReason`）。不新增錯誤碼、不改端點 D（其 DENIED 回應本已定義） | 補既有已拍板設計（設計文件 §4.2 App 放棄流程、§5.2.3 第 3 點【必要】確認畫面「不是我」拒絕出口）之落地缺口：原 A–D 四端點無任何路徑可觸發 `DENIED`，使用者取消只能被動逾時成 `EXPIRED`，流失 warn-only proximity 態勢下唯一的「使用者主動識破中繼」稽核/偵測訊號。非新架構決策，屬 S1/S2/S5 已簽核範圍內的落地。 |
| D17 | 跨裝置 QR 登入簽發的 session JWT `amr` 多帶 `"xdev"`（＝`["fido","hwk","xdev"]`），作為下游辨識較弱路徑、對敏感操作要求 step-up 的權威依據；fido-server 只標記不強制（enforcement 落下游） | 情境三使用範圍限縮（S7，擁有者已拍板）的落地機制，本次新增；對齊 D15 責任邊界。見 §1.3 |
| D-附 | 允許使用者撤銷至 0 個 FIDO 裝置（回退純帳密） | 對齊帳密永久保留、FIDO 為加掛選項 |
| D-附2 | proximity（出口 IP）檢查採**警示制**（只警示不阻擋），不設 4xx 拒絕碼，改於成功回應夾帶 `proximity`/`warnings` 欄位並寫 `audit_log.detail.proximityMismatch` | 情境三 proximity 政策（S2，擁有者拍板 warn-only，與設計文件原 strict 建議不同）。見 §1.4 / §3.4 |

> 複核通過後，建議將 D2/D3/D4/D5/D10 等影響架構的項目回填至 CLAUDE.md「已確認的關鍵架構決策」表，並將本合約交接 dev-engineer 進入實作。D12/D13/D14 已由人類拍板（origin-binding.md OB1/OB4/OB5/OB6），對應 `tenant_app_bindings` 表已定案於 db-schema.md 第 9 節。
