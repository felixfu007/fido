# WebAuthn Origin 綁定架構 — 瀏覽器 vs 原生 App

- 版本：v2（OB1–OB6 已由人類拍板定案）
- 最後更新：2026-07-22
- 適用架構情境：A（標準 WebAuthn，同裝置）
- 對齊文件：`d:\fido\CLAUDE.md`、`d:\fido\docs\api-contract.md`、`d:\fido\docs\db-schema.md`、`d:\fido\docs\android-poc-checklist.md`
- 讀者：dev-engineer（provider / server 實作）、devops-engineer（租戶 onboarding、schema）、qa-engineer
- 觸發來源：Android Credential Provider PoC 收尾時發現的開放問題（見 CLAUDE.md「目前階段」、`PocConfig.kt` Javadoc）

> 本文件把「WebAuthn ceremony 的 origin 綁定，在多租戶平台下如何安全傳遞」定案為設計。
>
> **本文件原列 OB1–OB6 六項待確認決策，已於 2026-07-22 由人類全數拍板**（結論見[第 8 節](#8-補充決策定案清單ob1ob6)）。已據此回填 `CLAUDE.md`（六→七張核心表、架構情境 A 補存取情境）、`db-schema.md`（新增 `tenant_app_bindings` 表 / DB17）、`api-contract.md`（`ORIGIN_NOT_ALLOWED` 錯誤碼 D12、稽核 originType D13、無 App 管理 API D14）、`infra/sql/002`+`003`。本版本已移除「待確認」語氣，僅在必要處保留「已評估未採用」的替代方案理由供追溯。

---

## 目錄

1. [問題定義：為什麼 origin 綁定是安全關鍵](#1-問題定義為什麼-origin-綁定是安全關鍵)
2. [存取情境範圍的釐清與建議](#2-存取情境範圍的釐清與建議)
3. [Origin 綁定原理速覽](#3-origin-綁定原理速覽)
4. [情境一：瀏覽器存取購物網站（v1 基準，無需新增設定）](#4-情境一瀏覽器存取購物網站v1-基準無需新增設定)
5. [情境二：購物網站原生 App 直呼 Credential Manager（Digital Asset Links）](#5-情境二購物網站原生-app-直呼-credential-managerdigital-asset-links)
6. [`FidoCredentialProviderService` 端的 origin 判定邏輯](#6-fidocredentialproviderservice-端的-origin-判定邏輯)
7. [與 `api-contract.md` / 資料模型的關聯與建議改動](#7-與-api-contractmd--資料模型的關聯與建議改動)
8. [補充決策定案清單（OB1–OB6）](#8-補充決策定案清單ob1ob6)
9. [交接與後續行動](#9-交接與後續行動)

---

## 1. 問題定義：為什麼 origin 綁定是安全關鍵

WebAuthn 的防釣魚（anti-phishing）保證，核心在 `clientDataJSON.origin`：它必須是「呼叫方**真實、經作業系統擔保**的來源」，而**不能由呼叫方自行宣稱**。伺服器在驗證 ceremony 時，會拿這個 origin 比對「這個租戶允許的來源清單」，不符即拒絕。若 origin 可被任意宣稱，一個釣魚站／惡意 App 就能誘導使用者對「攻擊者的 origin」簽章、再把簽章重放到真正的網站，防釣魚保證即失效。

本專案現況（已可運作的地基）：

- 伺服器端 `OriginValidator`（`fido-server/.../service/OriginValidator.java`）已會把 `clientDataJSON.origin` 比對租戶的 `tenants.expected_origin`（`db-schema.md` §3，`NVARCHAR(512)`，**可存單一字串或 JSON 陣列字串**）。不符即比照 `api-contract.md` §2.2 / §3.2 步驟 2/3 的 origin 檢核失敗處理。
- 因此**伺服器端已是 origin 的最終權威**：不論 Android 端寫入什麼 origin，最後都要通過租戶允許清單這關。本文件的設計以「維持伺服器為最終權威」為原則。

缺口在 **Android 端如何取得「可信、動態」的 origin**：PoC 目前用 `PocConfig.ORIGIN`（寫死 `https://shop.example.com`，見 `PocConfig.kt` 與 `ClientDataBuilder` 呼叫點 `CreatePasskeyActivity.kt:118`、`GetPasskeyActivity.kt:102`）。寫死值在單租戶 PoC 走得通，但正式多租戶環境**絕不可寫死**——provider 服務多個租戶、多個 RP ID，origin 必須從「這次到底是誰在呼叫 Credential Manager」動態、且經驗證地取得。

---

## 2. 存取情境範圍（定案）

> **定案（OB1）**：v1 **provider 程式碼一併支援瀏覽器與原生 App 兩種情境**；**原生 App 情境對每個租戶採 opt-in**——租戶須完成 `assetlinks.json` onboarding 並由平台登錄其 App 簽章指紋，才算開通該租戶的 App 內 FIDO 登入。純瀏覽器租戶零額外設定即可使用。以下 2.1–2.3 保留推導脈絡供追溯。

### 2.1 背景：CLAUDE.md 原未明訂範圍

CLAUDE.md 定義了「採用 FIDO 登入的購物網站」這個角色，但原**未定義使用者是透過手機瀏覽器、還是購物網站自家原生 Android App 完成 FIDO 登入**。`api-contract.md` 前言只說明「所有 REST 端點的呼叫方是購物網站**後端**（server-to-server）」，並未限定使用者端（前端）是瀏覽器還是 App——這兩件事是不同層次，後端 server-to-server 的事實對「前端 origin 怎麼來」不構成答案。此範圍缺口即 OB1，現已拍板（見上方定案）。

### 2.2 兩種情境的成本/風險差異

| 面向 | 瀏覽器情境 | 原生 App 情境 |
|---|---|---|
| Android 系統對 origin 的擔保 | 瀏覽器是系統 **pre-trusted caller**，系統會帶入真實網頁 origin | 一般 App 不被自動信任，須靠 **Digital Asset Links** 授權 |
| 租戶維運責任 | **無**（不需在網域放任何檔案） | **每租戶自理**：在自己網域放 `assetlinks.json` 宣告 App 指紋，並把 App 簽章指紋交給平台登錄 |
| 平台端新增工作 | 幾乎無（provider 動態取 web origin 即可） | provider 需辨識原生 App 呼叫方並算出 app origin；伺服器允許清單需納入 app origin |
| 實機驗證需求 | 低（PoC item 1 已驗 provider 掛載） | 高（DAL 驗證、OEM 差異須實機，對齊 PoC item 10） |
| 對使用者的價值 | 網頁購物直接可用 | App 內購物免跳瀏覽器、體驗較佳 |

### 2.3 定案內容與理由（OB1）

**v1 範圍（已拍板）：**

1. **瀏覽器情境為 v1 強制基準（mandatory baseline）。** 理由：零租戶維運成本、對齊 PoC 假設、所有購物網站都能立即使用的最小可行路徑。
2. **原生 App 情境：provider 與資料模型一併支援，對每個租戶採 opt-in 啟用。** 租戶須完成 `assetlinks.json` onboarding + 平台登錄其 App 簽章指紋（寫入 `tenant_app_bindings`）才算開通。理由：
   - **provider 程式碼無論如何都必須改成動態取 origin**（不能寫死），而正確的動態解析**本來就要同時處理瀏覽器與原生 App 兩條路徑**（見第 6 節演算法）。一次做對成本幾乎等同只做瀏覽器，且避免日後返工重測。
   - **原生 App 的真實 DAL 行為與 OEM 相容性仍須實機驗證**（目前無實機，對齊 PoC「條件式通過 pending 實機」）。opt-in 設計讓「某租戶實際開通 App 登入」與「平台整體上線」脫鉤：沒有租戶開通 App binding 前，此路徑不影響任何人。

即「支援原生 App」拆成「程式碼能力（v1 即做）」與「對個別租戶的營運承諾（opt-in、需完成 onboarding 與實機驗證）」兩件事。

---

## 3. Origin 綁定原理速覽

WebAuthn `clientDataJSON.origin` 在 Android 上有兩種合法形態：

- **Web origin**：如 `https://shop.example.com`。當呼叫 Credential Manager 的是**瀏覽器**（系統認證的 privileged caller）時使用。
- **App origin（apk-key-hash 格式）**：`android:apk-key-hash:<BASE64URL_NOPAD( SHA-256( App 簽章憑證 DER ) )>`。當呼叫方是**原生 App 自身**（非代表某網頁）時使用。這個值由呼叫 App 的**簽章憑證**推導，App 無法偽造他人的簽章（OS 層擔保簽章身分），因此本身即具不可偽造性。

關鍵差異在「誰擔保這個 origin 對應到某個 RP ID 網域」：

- 瀏覽器：系統知道使用者當下瀏覽的真實網域，直接把 web origin 交給 Credential Manager／provider，**天生綁定網域**。
- 原生 App：`android:apk-key-hash:...` 本身只證明「是這支 App」，**不證明這支 App 有權代表 `shop.example.com` 網域**。這個「App ↔ 網域」的授權，就是 **Digital Asset Links** 要補上的環節，並由伺服器端的「允許 origin 清單」做最終把關。

---

## 4. 情境一：瀏覽器存取購物網站（v1 基準，無需新增設定）

**為什麼安全、為什麼不需要額外設定：**

1. Android 系統維護一份「受信任瀏覽器（privileged callers）」清單。當使用者在瀏覽器內的購物網站網頁觸發 WebAuthn，瀏覽器呼叫 Credential Manager 時，只有清單內的瀏覽器**被允許代表某個 web origin 發起請求**。
2. 系統把「使用者當下真實瀏覽的網域」對應的 web origin 一路帶到我們的 provider。provider 端透過 `CallingAppInfo.getOrigin(privilegedAllowlist)` 取得這個經驗證的 web origin（見第 6 節）。惡意 App 冒充瀏覽器身分時，因簽章不符會被 `getOrigin(...)` 擋下。
3. 這個 web origin（如 `https://shop.example.com`）本來就等於租戶的 `expected_origin`，伺服器端 `OriginValidator` 直接比對即通過。

**因此瀏覽器情境「新增工作」清單：**

- 租戶端：**無**。不需在網域放任何檔案。`expected_origin` 填該租戶的 `https://<網域>`（onboarding 時本來就要設，非新增）。
- 平台伺服器端：**無**（`OriginValidator` + `expected_origin` 已就緒）。
- Android provider 端：只需把寫死的 `PocConfig.ORIGIN` 換成「從 `CallingAppInfo` 動態取得的 web origin」（第 6 節）。這是 provider 從 PoC 進入正式開發本來就要做的改動，非為原生 App 額外付出。

唯一需要維護的新資料，是 provider 內建的「受信任瀏覽器允許清單（privileged browser allowlist）」——package name + 簽章指紋。這是 provider 自帶的靜態資產（各家瀏覽器公開的簽章），非逐租戶資料，見第 6.3 節。

---

## 5. 情境二：購物網站原生 App 直呼 Credential Manager（Digital Asset Links）

適用於：購物網站有自己的原生 Android App，使用者在 App 內（非瀏覽器、非 WebView 網頁）直接觸發 FIDO 登入。

### 5.1 運作機制（App ↔ 網域授權）

1. 購物網站 App 呼叫 Credential Manager，`requestJson` 內 `rp.id = shop.example.com`。
2. 因為呼叫方是一般 App、不是 pre-trusted 瀏覽器，系統不會給 web origin。App 對應的 origin 是 `android:apk-key-hash:<H>`，其中 `<H>` 由該 App 的簽章憑證推導。
3. **App 有沒有權代表 `shop.example.com`**，靠 Digital Asset Links 宣告：購物網站在自己網域根目錄放 `https://shop.example.com/.well-known/assetlinks.json`，宣告「package = `com.shop.example`、簽章指紋 = `<fingerprint>` 的 App，被授權代表本網域取用登入憑證」。
4. 平台的最終把關在**伺服器端**：`fido-server` 只有在收到的 `android:apk-key-hash:<H>` origin **屬於該租戶已登錄的授權 App 指紋**時才放行（把 app origin 納入該租戶的 `expected_origin` 允許清單）。惡意 App 即使誘使使用者選到我們的 provider、產生一個以「自己簽章」為基礎的 app origin，也會因該 origin 不在租戶允許清單而被伺服器拒絕。

> 設計原則：**App→網域的 DAL 宣告負責讓「系統/生態」承認這支 App 能為該網域做事；伺服器端的租戶 origin 允許清單負責做「不可繞過的最終授權把關」。** 兩者方向互補，缺一則有繞過風險。

### 5.2 每個租戶（購物網站）需要做的事

**(A) 在自己網域放 `assetlinks.json`**

路徑（固定、不可改）：`https://<rpId>/.well-known/assetlinks.json`，以 `Content-Type: application/json`、HTTPS、可公開讀取提供。內容格式：

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

欄位說明：

| 欄位 | 內容 | 說明 |
|---|---|---|
| `relation` | `["delegate_permission/common.get_login_creds"]` | 針對「取用登入憑證」的委派關係（passkey / credential 用途）。若該 App 另有其他 App Links 需求可並存多個 relation 物件。 |
| `target.namespace` | `android_app` | 固定 |
| `target.package_name` | 該 App 的 applicationId | 如 `com.shop.example` |
| `target.sha256_cert_fingerprints` | App **簽章憑證**的 SHA-256 指紋（冒號分隔大寫 hex） | 上架用的正式簽章；若採 Google Play App Signing，須填 **Google 重新簽章後**的憑證指紋，可自 Play Console 取得。**測試/正式簽章不同要各列一筆**。 |

**(B) 把 App 簽章指紋（或 apk-key-hash origin）交給平台登錄**

租戶把上述 `package_name` + `sha256_cert_fingerprints` 提供給平台（FIDO 服務營運方），平台換算成 `android:apk-key-hash:<H>` 形式的 app origin，**寫入該租戶的 `tenant_app_bindings` 一列**（見 5.3）。此步是伺服器端把關的資料來源。

> `<H>` = `BASE64URL_NOPAD( SHA-256( 簽章憑證 DER ) )`。注意 `assetlinks.json` 用的是**冒號分隔 hex** 呈現同一份 SHA-256，而 origin 用的是 **base64url** 呈現，兩者是同一雜湊的不同編碼；平台登錄工具需負責換算。

### 5.3 `fido-server` / 資料模型的影響（定案 OB3：新增 `tenant_app_bindings` 表）

> **定案（OB3）**：採**新增第七張核心表 `tenant_app_bindings`**（完整 schema 見 `db-schema.md` 第 9 節 / DB17），**不**採「在 `tenants` 加 JSON 欄位」的替代方案。CLAUDE.md「六張核心表」敘述已一併更新為七張。

- **origin 允許清單的組成**：伺服器驗證 origin 時，允許清單 = `tenants.expected_origin`（web origin，一律存在）∪ 該租戶 `tenant_app_bindings` 中 `status='ACTIVE'` 列的 `apk_key_hash_origin`（app origin，僅 opt-in 租戶有）。`OriginValidator` 現有的「逐一字串比對 allowlist」邏輯不變，只是允許清單的來源多了一個表；收到 app origin 與 web origin 的比對方式完全一致。範例允許清單：

  ```
  web  : "https://shop.example.com"                    ← tenants.expected_origin
  app  : "android:apk-key-hash:R2f...base64url...Xy"   ← tenant_app_bindings.apk_key_hash_origin (ACTIVE)
  ```

- **`tenant_app_bindings` 存什麼**：每列一支授權 App，含 `package_name`、`sha256_cert_fingerprint`（raw 32 bytes）、`apk_key_hash_origin`（換算好的比對字串）、`label`、`status`（軟刪除支援簽章輪替）等。欄位權威定義見 `db-schema.md` 第 9 節。

- **選 B（新表）而非選 A（`tenants` JSON 欄位）的理由（供追溯）**：新表可逐筆稽核 App 授權的增刪與輪替、支援一租戶多 App（正式/測試簽章並存）、指紋可建唯一索引防重複登錄；代價是核心表由六增為七，已回填 CLAUDE.md 與 db-schema.md（DB17）。選項 A 的 JSON 欄位雖不動表數，但不利索引與多筆稽核，經評估未採用。

### 5.4 為什麼**不**記在 `bound_devices`

明確結論：**記在租戶層的 `tenant_app_bindings`，不可記在 `bound_devices`（裝置層）。** 理由：

- `bound_devices` 記錄的是**終端使用者持有 FIDO 金鑰的實體手機**（其 TEE/StrongBox 硬體安全屬性），是**每位使用者、每台裝置**一列。
- 購物網站 App 的簽章指紋是**整個租戶共用的一個屬性**（同一支 App，所有該租戶使用者共用同一簽章），與「使用者用哪台手機」完全正交。把它塞進 `bound_devices` 會造成每列重複儲存同一份租戶級資料、且語意錯置。
- 故 App 授權指紋屬**租戶層**資料（`tenant_app_bindings`），與 `bound_devices` 無關。

---

## 6. `FidoCredentialProviderService` 端的 origin 判定邏輯

本節具體到可讓 dev-engineer 直接據以修改 provider 程式碼。**核心改動：移除 `PocConfig.ORIGIN` 寫死值，改由呼叫方資訊動態解析出「經驗證的 origin」。**

### 6.1 origin 資訊從哪裡來

實際組 `clientDataJSON` 的地方是兩個由系統以 PendingIntent 拉起的 Activity：`CreatePasskeyActivity`（註冊）與 `GetPasskeyActivity`（登入）。它們已透過 `PendingIntentHandler` 取得 provider 請求物件，該物件帶有呼叫方資訊 `CallingAppInfo`：

- 註冊：`PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)?.callingAppInfo`
- 登入：`PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)?.callingAppInfo`

`CallingAppInfo` 提供：`packageName`、`signingInfo`（呼叫方簽章憑證）、以及 `getOrigin(privilegedAllowlist: String)`。

### 6.2 判定演算法（provider 應如何判斷 origin 可信、何時拒絕）

```
fun resolveTrustedOrigin(callingAppInfo, rpIdFromRequestJson): OriginDecision {

    // (1) 瀏覽器路徑：呼叫方若為 allowlist 內的受信任瀏覽器且代表某 web origin，
    //     getOrigin() 會回傳該 web origin；若呼叫方冒充 privileged 但簽章不符 → 拋例外。
    val webOrigin: String? =
        try {
            callingAppInfo.getOrigin(PRIVILEGED_BROWSER_ALLOWLIST_JSON)
        } catch (e: IllegalArgumentException | IllegalStateException) {
            // 呼叫方宣稱是 privileged caller 但不在 allowlist / 簽章不符
            return Reject("UNTRUSTED_PRIVILEGED_CALLER")   // 疑似冒充瀏覽器 → 拒絕，不簽任何東西
        }

    if (webOrigin != null) {
        // 受信任瀏覽器代表某網頁：origin 就是這個 web origin（情境一）
        return UseOrigin(webOrigin, sourceType = WEB)
    }

    // (2) 原生 App 路徑：呼叫方是一般 App 自身（webOrigin == null）。
    //     以呼叫方「實際簽章憑證」計算 apk-key-hash origin（App 無法偽造他人簽章）。
    val appOrigin = "android:apk-key-hash:" +
        base64UrlNoPad( sha256( currentSignerCertDer(callingAppInfo.signingInfo) ) )
    return UseOrigin(appOrigin, sourceType = NATIVE_APP)
}
```

要點：

- provider **永不寫入自己臆造的 origin**。web origin 只有在 `getOrigin(allowlist)` 對受信任瀏覽器回傳時採用；app origin 一律由**呼叫方真實簽章**推導。兩者都不是呼叫方「隨口宣稱」的字串，故不可偽造。
- 解出的 origin 交給 `ClientDataBuilder.build(..., origin = resolvedOrigin)`（取代目前的 `PocConfig.ORIGIN`）。
- **最終授權仍由伺服器把關**：即使某惡意 App 走到 (2) 產生自己的 app origin，該 origin 不在目標租戶 `expected_origin` 允許清單，`fido-server` 會拒絕。provider 端不需要、也不應該自行持有各租戶的 allowlist（那是伺服器的權威資料）。

### 6.3 拒絕條件（防惡意 App 冒充）

provider 端應在以下情況直接拒絕（回 `CreateCredentialUnknownException` / `GetCredentialException`，不產生任何簽章）：

1. **冒充受信任瀏覽器**：`getOrigin(allowlist)` 因簽章不符拋例外（呼叫方 package 在 allowlist 內但簽章對不上，典型的重打包冒充）。
2. **無法取得可信呼叫方身分**：`callingAppInfo == null` 或 `signingInfo` 取不到簽章憑證。
3. **（原生 App 路徑的深度防禦，定案 OB2：預設不做）** provider **預設不**自行抓取 `https://<rpId>/.well-known/assetlinks.json` 做 App↔網域驗證，改由**伺服器端 `tenant_app_bindings` 允許清單做唯一不可繞過的把關**。理由：伺服器已是 origin 最終權威；provider 端抓 DAL 會引入網路相依與離線可用性問題。若日後有需要，可再加此層深度防禦，但非 v1 必要。

### 6.4 受信任瀏覽器 allowlist（provider 靜態資產）

- `PRIVILEGED_BROWSER_ALLOWLIST_JSON` 是 provider **內建的靜態設定**（各主流瀏覽器公開的 package name + 簽章指紋），格式為 Credential Manager 定義的 privileged allowlist JSON。它**不是逐租戶資料**——所有租戶共用同一份「哪些瀏覽器可信」的清單。
- 建議與 Android / Google 發布的公開瀏覽器 allowlist 對齊，並在後續版本可更新（新瀏覽器、指紋輪替）。**具體採用哪份 allowlist、如何維護更新，屬實作細節，dev-engineer 於實作時確認，非本文件拍板**。

### 6.5 對現有 PoC 程式碼的具體改動指引（給 dev-engineer）

- 移除 `PocConfig.ORIGIN` 的使用（可保留 `PocConfig` 其餘 PoC 欄位或整體隨 harness 一併退場，視 PoC harness 去留而定）。
- `CreatePasskeyActivity`（約 `:115-119`）與 `GetPasskeyActivity`（約 `:99-103`）呼叫 `ClientDataBuilder.build(...)` 時，`origin` 改用 6.2 的 `resolveTrustedOrigin(...)` 結果。
- `rpId` 應以 `requestJson` 內的 `rp.id` 為準（`CreatePasskeyActivity.parseCreationOptions` 已解析 `rp.id`），不要 fallback 到 `PocConfig.RP_ID`；provider 產出的 `authenticatorData.rpIdHash` 與 clientData 一致，最終仍由伺服器對租戶 `rp_id` 把關。
- 這些改動與硬體 attestation（PoC 關鍵項目 2–5）無關，屬**正向流程接線**，模擬器即可驗證；原生 App 路徑的真實 DAL 行為與 OEM 差異則對齊 PoC「pending 實機」收尾。

---

## 7. 與 `api-contract.md` / 資料模型的關聯（已回填）

> 以下改動已於 OB1–OB6 拍板後**實際回填** `api-contract.md` 與 `db-schema.md`。

### 7.1 安全性核心：origin 驗證路徑不變

註冊/登入 API 已把 `clientDataJSON` 交由伺服器解析並以允許清單比對 origin（§2.2 步驟 2、§3.2 步驟 3）。原生 App 的 `android:apk-key-hash:...` origin 只是允許清單裡多一種字串型態，**驗證路徑不變、請求/回應欄位不變**。允許清單來源從「僅 `tenants.expected_origin`」擴充為「`expected_origin`（web）+ `tenant_app_bindings`（app）」。

### 7.2 已回填 `api-contract.md` 的項目

1. **§1.2 補「Origin 綁定」段（D12）**：明訂 origin 可為 web origin 或 `android:apk-key-hash:...`，兩者比對同一份租戶允許清單（web 來源 `expected_origin`、app 來源 `tenant_app_bindings`）。
2. **新增 `403 ORIGIN_NOT_ALLOWED` 錯誤碼（D12 / OB4）**：與 `RP_ID_MISMATCH` 區分，已加入 §1.4 通用錯誤碼表與 §2.2/§3.2 主要錯誤清單。
3. **稽核記 origin 型別（D13 / OB5）**：`registration/result` / `authentication/result` 於 `audit_log.detail` 記 `originType`(`WEB`/`NATIVE_APP`)，用既有 JSON `detail` 欄位、**無 schema 變更**。
4. **§5.3 新增「租戶已授權 App 清單（v1 無 API）」說明（D14 / OB6）**：見 7.3。

### 7.3 租戶 App 指紋登錄：v1 無 API 端點（定案 OB6）

> **定案（OB6）**：租戶「登錄/輪替授權 App 簽章指紋」在 v1 **採人工 onboarding**，**不新增任何 REST 端點**。

- 租戶完成 `assetlinks.json` 部署後，把 App package + SHA-256 簽章指紋交給平台營運方，由營運方直接寫入 `tenant_app_bindings`（與「租戶開通、API Key 發放」同屬人工 onboarding 步驟）。
- 不新增端點的理由：中小規模、一租戶通常一支 App，人工足夠；且 origin 驗證直接讀 `tenant_app_bindings`，ceremony 端點請求/回應結構無需改動。
- 日後若原生 App 租戶增多需自助管理，再評估新增唯讀查詢/設定端點；`tenant_app_bindings.binding_uid` 已預留為對外識別。此擴充非 v1 範圍。

### 7.4 資料模型改動彙整（已定案）

| 改動 | 狀態 | schema 變更 | 對應 |
|---|---|---|---|
| origin 允許清單納入 app origin（`expected_origin` ∪ `tenant_app_bindings`） | 原生 App 情境必要 | 無（沿用既有比對邏輯） | 5.3 |
| 新增 `tenant_app_bindings` 表存租戶 App 授權 | 已定案（OB3 選項 B） | **有**（db-schema.md 第 9 節 / DB17、infra/sql 002+003） | 5.3 / OB3 |
| `audit_log.detail` 記 `originType` | 已定案（OB5） | 無（既有 JSON 欄位） | 7.2 / OB5 |
| `ORIGIN_NOT_ALLOWED` 錯誤碼 | 已定案（OB4） | 無（API 合約 §1.4） | 7.2 / OB4 |

---

## 8. 補充決策定案清單（OB1–OB6）

> 六項均已於 2026-07-22 由人類拍板。下表為**最終定案**（非提案）。已回填的文件見「回填狀態」欄。

| 編號 | 定案內容 | 理由 | 回填狀態 |
|---|---|---|---|
| **OB1** | v1 **provider 一併支援瀏覽器與原生 App 兩情境**；**原生 App 對每個租戶採 opt-in**（須完成 `assetlinks.json` onboarding + 平台登錄 App 指紋才開通）。 | provider 動態取 origin 本就須同時處理兩路徑，一次做滿避免返工；原生 App 的實機/DAL/OEM 驗證與 onboarding 成本以 opt-in 與上線時程脫鉤。 | CLAUDE.md 情境 A 敘述已補存取情境；本文件第 2 節 |
| **OB2** | provider 端**預設不**自行抓 `assetlinks.json`；由伺服器 `tenant_app_bindings` 允許清單做唯一不可繞過把關；DAL 深度防禦列日後選配。 | 伺服器已是 origin 最終權威；provider 抓 DAL 引入網路相依與離線問題。 | 本文件第 6.3 節 |
| **OB3** | **新增第七張核心表 `tenant_app_bindings`**（未採 `tenants` JSON 欄位方案）。 | 可逐筆稽核/輪替 App 授權、支援一租戶多 App、指紋建唯一索引；代價是六→七表。 | db-schema.md 第 9 節 / DB17；infra/sql 002+003；CLAUDE.md 六→七表 |
| **OB4** | 新增 `403 ORIGIN_NOT_ALLOWED`，與 `RP_ID_MISMATCH` 區分。 | 便於購物網站分辨錯因。 | api-contract.md §1.4 / §2.2 / §3.2 / D12 |
| **OB5** | `registration/result` / `authentication/result` 於 `audit_log.detail` 記 `originType`(WEB/NATIVE_APP)。 | 事後鑑識登入來源；用既有 JSON 欄位、無 schema 變更。 | api-contract.md §2.2 / §3.2 核心表對應 / D13 |
| **OB6** | 租戶登錄/輪替 App 指紋 v1 採**人工 onboarding**，不納入 REST 合約。 | 中小規模、原生 App 租戶初期少，人工足夠。 | api-contract.md §5.3 / D14 |

---

## 9. 交接與後續行動

**已回填的文件（本次）：**

- `CLAUDE.md`：資料庫列六→七張核心表、補 `tenant_app_bindings`；架構情境 A 補「存取情境（瀏覽器 + 原生 App opt-in）」敘述。
- `db-schema.md`：新增第 9 節 `tenant_app_bindings` 表（DB17）、更新總覽/ER 圖/索引總表/保留章節。
- `api-contract.md`：`ORIGIN_NOT_ALLOWED`（D12）、稽核 `originType`（D13）、§5.3 無 App 管理 API（D14）、§1.2 Origin 綁定段。
- `infra/sql/002_create_tables.sql`、`003_create_indexes.sql`：`tenant_app_bindings` 建表與索引。

**交接 dev-engineer（可動工）：**

- provider：把寫死的 `PocConfig.ORIGIN` 換成第 6 節的動態 origin 解析（瀏覽器路徑 + 原生 App 路徑 + 拒絕冒充邏輯）。屬正向流程接線、模擬器可驗、與硬體 attestation 無關。
- fido-server：origin 允許清單改為 `tenants.expected_origin` ∪ 該租戶 `tenant_app_bindings` active 列的 `apk_key_hash_origin`；origin 不符回 `403 ORIGIN_NOT_ALLOWED`；`audit_log.detail` 記 `originType`；新增 `tenant_app_bindings` JPA 實體與 H2 對應 schema。
- 原生 App 路徑的真實 DAL 行為與 OEM 相容性，對齊 PoC item 10「pending 實機」收尾驗證。

**交接 devops-engineer：**

- `infra/sql/002`+`003` 已含第七張表，需**重新在 LocalDB 驗證含 `tenant_app_bindings` 的 schema 建置**是否正確（見本文件觸發的協調提醒）。

**營運流程（人工 onboarding，OB6）：**

- 原生 App 租戶開通時，營運方協助部署 `assetlinks.json` 檢查、換算 apk-key-hash origin、寫入 `tenant_app_bindings`。
