# WebAuthn Origin 綁定架構 — 瀏覽器 vs 原生 App

- 版本：v1（草案，待人工複核）
- 最後更新：2026-07-22
- 適用架構情境：A（標準 WebAuthn，同裝置）
- 對齊文件：`d:\fido\CLAUDE.md`、`d:\fido\docs\api-contract.md`、`d:\fido\docs\db-schema.md`、`d:\fido\docs\android-poc-checklist.md`
- 讀者：人類決策者（範圍拍板）、dev-engineer（provider / server 實作）、devops-engineer（租戶 onboarding）
- 觸發來源：Android Credential Provider PoC 收尾時發現的開放問題（見 CLAUDE.md「目前階段」末段、`PocConfig.kt` Javadoc）

> 本文件把「WebAuthn ceremony 的 origin 綁定，在多租戶平台下如何安全傳遞」定案為設計。
>
> 凡標記 **【本文件補充決策 OBn，待人類確認】** 者，為 CLAUDE.md / 既有合約尚未涵蓋、由本文件先行提出、**尚未拍板**的架構決策，集中列於[第 8 節](#8-本文件補充決策清單待人類確認)。本文件不片面改寫 CLAUDE.md。凡標記 **【待人類確認：範圍】** 者，是需要人類明確回答才能定案的範圍問題，本文件只給建議與理由。

---

## 目錄

1. [問題定義：為什麼 origin 綁定是安全關鍵](#1-問題定義為什麼-origin-綁定是安全關鍵)
2. [存取情境範圍的釐清與建議](#2-存取情境範圍的釐清與建議)
3. [Origin 綁定原理速覽](#3-origin-綁定原理速覽)
4. [情境一：瀏覽器存取購物網站（v1 基準，無需新增設定）](#4-情境一瀏覽器存取購物網站v1-基準無需新增設定)
5. [情境二：購物網站原生 App 直呼 Credential Manager（Digital Asset Links）](#5-情境二購物網站原生-app-直呼-credential-managerdigital-asset-links)
6. [`FidoCredentialProviderService` 端的 origin 判定邏輯](#6-fidocredentialproviderservice-端的-origin-判定邏輯)
7. [與 `api-contract.md` / 資料模型的關聯與建議改動](#7-與-api-contractmd--資料模型的關聯與建議改動)
8. [本文件補充決策清單（待人類確認）](#8-本文件補充決策清單待人類確認)
9. [交接與後續行動](#9-交接與後續行動)

---

## 1. 問題定義：為什麼 origin 綁定是安全關鍵

WebAuthn 的防釣魚（anti-phishing）保證，核心在 `clientDataJSON.origin`：它必須是「呼叫方**真實、經作業系統擔保**的來源」，而**不能由呼叫方自行宣稱**。伺服器在驗證 ceremony 時，會拿這個 origin 比對「這個租戶允許的來源清單」，不符即拒絕。若 origin 可被任意宣稱，一個釣魚站／惡意 App 就能誘導使用者對「攻擊者的 origin」簽章、再把簽章重放到真正的網站，防釣魚保證即失效。

本專案現況（已可運作的地基）：

- 伺服器端 `OriginValidator`（`fido-server/.../service/OriginValidator.java`）已會把 `clientDataJSON.origin` 比對租戶的 `tenants.expected_origin`（`db-schema.md` §3，`NVARCHAR(512)`，**可存單一字串或 JSON 陣列字串**）。不符即比照 `api-contract.md` §2.2 / §3.2 步驟 2/3 的 origin 檢核失敗處理。
- 因此**伺服器端已是 origin 的最終權威**：不論 Android 端寫入什麼 origin，最後都要通過租戶允許清單這關。本文件的設計以「維持伺服器為最終權威」為原則。

缺口在 **Android 端如何取得「可信、動態」的 origin**：PoC 目前用 `PocConfig.ORIGIN`（寫死 `https://shop.example.com`，見 `PocConfig.kt` 與 `ClientDataBuilder` 呼叫點 `CreatePasskeyActivity.kt:118`、`GetPasskeyActivity.kt:102`）。寫死值在單租戶 PoC 走得通，但正式多租戶環境**絕不可寫死**——provider 服務多個租戶、多個 RP ID，origin 必須從「這次到底是誰在呼叫 Credential Manager」動態、且經驗證地取得。

---

## 2. 存取情境範圍的釐清與建議

### 2.1 CLAUDE.md 現況：範圍未明訂

CLAUDE.md 定義了「採用 FIDO 登入的購物網站」這個角色，但**未定義使用者是透過手機瀏覽器、還是購物網站自家原生 Android App 完成 FIDO 登入**。`api-contract.md` 前言只說明「所有 REST 端點的呼叫方是購物網站**後端**（server-to-server）」，並未限定使用者端（前端）是瀏覽器還是 App——這兩件事是不同層次，後端 server-to-server 的事實對「前端 origin 怎麼來」不構成答案。

因此「v1 是否需要同時支援瀏覽器與原生 App 兩種存取情境」是一個 **【待人類確認：範圍】** 的問題，本文件不片面認定。

### 2.2 兩種情境的成本/風險差異

| 面向 | 瀏覽器情境 | 原生 App 情境 |
|---|---|---|
| Android 系統對 origin 的擔保 | 瀏覽器是系統 **pre-trusted caller**，系統會帶入真實網頁 origin | 一般 App 不被自動信任，須靠 **Digital Asset Links** 授權 |
| 租戶維運責任 | **無**（不需在網域放任何檔案） | **每租戶自理**：在自己網域放 `assetlinks.json` 宣告 App 指紋，並把 App 簽章指紋交給平台登錄 |
| 平台端新增工作 | 幾乎無（provider 動態取 web origin 即可） | provider 需辨識原生 App 呼叫方並算出 app origin；伺服器允許清單需納入 app origin |
| 實機驗證需求 | 低（PoC item 1 已驗 provider 掛載） | 高（DAL 驗證、OEM 差異須實機，對齊 PoC item 10） |
| 對使用者的價值 | 網頁購物直接可用 | App 內購物免跳瀏覽器、體驗較佳 |

### 2.3 本文件建議

**建議 v1 範圍：**

1. **瀏覽器情境列為 v1 的強制基準（mandatory baseline）。** 理由：零租戶維運成本、對齊 PoC 目前假設、是所有購物網站都能立即使用的最小可行路徑。
2. **原生 App 情境：provider 與資料模型「設計上一併支援」，但「正式啟用」採每租戶 opt-in，且是否納入 v1 正式承諾範圍列為待人類確認。** 理由分兩層：
   - **provider 程式碼無論如何都必須改成動態取 origin**（不能寫死），而「動態取 origin」的正確實作**本來就要同時處理瀏覽器與原生 App 兩條路徑**（見第 6 節演算法）。把兩條路徑一次做對，成本幾乎等同只做瀏覽器，卻能避免日後回頭改 provider + 重測。故**程式碼層面建議一次做滿**。
   - **但「對外承諾支援某租戶的原生 App 登入」有額外落地成本**：該租戶要完成 `assetlinks.json` onboarding、平台要登錄其 App 指紋、且 DAL / OEM 相容性須實機驗證（目前無實機，對齊 PoC「條件式通過 pending 實機」）。因此「是否在 v1 就對租戶開放原生 App 登入」建議由人類依上線時程與是否有租戶實際需要 App 內登入來拍板。

換句話說：**建議把「支援原生 App」拆成「程式碼能力」與「營運承諾」兩件事**。前者建議 v1 就做（避免 provider 返工）；後者（含實機驗證與租戶 onboarding 流程）可 opt-in／可延後，待人類確認。詳見 OB1。

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

租戶把上述 `package_name` + `sha256_cert_fingerprints` 提供給平台（FIDO 服務營運方），平台換算成 `android:apk-key-hash:<H>` 形式的 app origin，**加入該租戶的 `expected_origin` 允許清單**（見 5.3）。此步是伺服器端把關的資料來源。

> `<H>` = `BASE64URL_NOPAD( SHA-256( 簽章憑證 DER ) )`。注意 `assetlinks.json` 用的是**冒號分隔 hex** 呈現同一份 SHA-256，而 origin 用的是 **base64url** 呈現，兩者是同一雜湊的不同編碼；平台登錄工具需負責換算。

### 5.3 `fido-server` / 資料模型的影響

**核心結論：不需要改動六張核心表的結構即可支援，`expected_origin` 已能承載。** 但為了「管理介面顯示與稽核」，建議補一個輕量的租戶層記錄，作法二選一（列為待人類確認 OB3）。

- **必做（無 schema 變更）**：`tenants.expected_origin` 已是「可存 JSON 陣列字串」的允許清單，`OriginValidator` 已支援逐一比對。原生 App 租戶的允許清單即同時含 web 與 app 兩型，例如：

  ```json
  ["https://shop.example.com", "android:apk-key-hash:R2f...base64url...Xy"]
  ```

  伺服器端**不需要新邏輯**——收到 app origin 時，比對邏輯與 web origin 完全一致（都是字串比對 allowlist）。這是本設計刻意選 `expected_origin` allowlist 而非在程式碼寫死 origin 型別判斷的好處。

- **建議做（供管理/稽核，作法待人類確認 OB3）**：`expected_origin` 只存一串 origin，人類看不出「哪個 origin 對應哪支 App、指紋是什麼、誰在何時登錄」。建議二選一保存這層 metadata：
  - **選項 A（輕量、不動核心表數）**：在 `tenants` 加一個可空欄位 `authorized_app_bindings NVARCHAR(MAX) NULL`，存 JSON 陣列，每筆含 `packageName` / `sha256CertFingerprint` / `apkKeyHashOrigin` / `label` / `addedAt`。優點：不新增表、對齊「六張核心表」既定範圍。缺點：JSON 欄位不利索引/多筆管理。
  - **選項 B（較正規、但突破六表）**：新增第七張表 `tenant_app_bindings`（`tenant_id` FK、`package_name`、`sha256_fingerprint`、`apk_key_hash_origin`、`status`、`created_at`…）。優點：可稽核每筆 App 授權的增刪、支援一租戶多 App。缺點：**牴觸 CLAUDE.md「六張核心表」的既定敘述**，屬需人類確認的架構調整。

  本文件傾向 **選項 A**（v1 中小規模、一租戶通常一支 App，JSON 欄位足夠；不動核心表數量、風險最小），但兩者皆列 OB3 待人類拍板。

### 5.4 為什麼**不**記在 `bound_devices`

任務提到「要不要在 `bound_devices` 或 `tenants` 記錄授權 App 指紋」。明確結論：**應記在 `tenants`（租戶層），不可記在 `bound_devices`（裝置層）。** 理由：

- `bound_devices` 記錄的是**終端使用者持有 FIDO 金鑰的實體手機**（其 TEE/StrongBox 硬體安全屬性），是**每位使用者、每台裝置**一列。
- 購物網站 App 的簽章指紋是**整個租戶共用的一個屬性**（同一支 App，所有該租戶使用者共用同一簽章），與「使用者用哪台手機」完全正交。把它塞進 `bound_devices` 會造成每列重複儲存同一份租戶級資料、且語意錯置。
- 故 App 授權指紋屬**租戶層**資料（`tenants` / 選項 B 的 `tenant_app_bindings`），與 `bound_devices` 無關。

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
3. （原生 App 路徑的深度防禦，選配、對齊 OB2）provider 亦可選擇在本地抓取 `https://<rpId>/.well-known/assetlinks.json` 驗證「呼叫 App 的 package+指紋確實被該網域授權」後才簽；但**這不是安全必要條件**（伺服器 allowlist 已是不可繞過的把關），且會引入 provider 端網路相依與離線可用性問題。建議**預設不做**、交給伺服器把關，是否加此層深度防禦列 OB2。

### 6.4 受信任瀏覽器 allowlist（provider 靜態資產）

- `PRIVILEGED_BROWSER_ALLOWLIST_JSON` 是 provider **內建的靜態設定**（各主流瀏覽器公開的 package name + 簽章指紋），格式為 Credential Manager 定義的 privileged allowlist JSON。它**不是逐租戶資料**——所有租戶共用同一份「哪些瀏覽器可信」的清單。
- 建議與 Android / Google 發布的公開瀏覽器 allowlist 對齊，並在後續版本可更新（新瀏覽器、指紋輪替）。**具體採用哪份 allowlist、如何維護更新，屬實作細節，dev-engineer 於實作時確認，非本文件拍板**。

### 6.5 對現有 PoC 程式碼的具體改動指引（給 dev-engineer）

- 移除 `PocConfig.ORIGIN` 的使用（可保留 `PocConfig` 其餘 PoC 欄位或整體隨 harness 一併退場，視 PoC harness 去留而定）。
- `CreatePasskeyActivity`（約 `:115-119`）與 `GetPasskeyActivity`（約 `:99-103`）呼叫 `ClientDataBuilder.build(...)` 時，`origin` 改用 6.2 的 `resolveTrustedOrigin(...)` 結果。
- `rpId` 應以 `requestJson` 內的 `rp.id` 為準（`CreatePasskeyActivity.parseCreationOptions` 已解析 `rp.id`），不要 fallback 到 `PocConfig.RP_ID`；provider 產出的 `authenticatorData.rpIdHash` 與 clientData 一致，最終仍由伺服器對租戶 `rp_id` 把關。
- 這些改動與硬體 attestation（PoC 關鍵項目 2–5）無關，屬**正向流程接線**，模擬器即可驗證；原生 App 路徑的真實 DAL 行為與 OEM 差異則對齊 PoC「pending 實機」收尾。

---

## 7. 與 `api-contract.md` / 資料模型的關聯與建議改動

> 以下為**建議**，供人類確認後再正式修訂 `api-contract.md` / `db-schema.md`；本文件不逕行改動那兩份文件。

### 7.1 安全性上「不需要」改 API 合約

現行註冊/登入 API 已把 `clientDataJSON` 交由伺服器解析並以 `expected_origin` 允許清單比對 origin（§2.2 步驟 2、§3.2 步驟 3）。原生 App 的 `android:apk-key-hash:...` origin 只是允許清單裡多一種字串型態，**驗證路徑不變、欄位不變**。因此就「把關安全」而言，API 合約可不動。

### 7.2 建議的（非強制）調整

1. **§1.2「RP ID 綁定」段補充 origin 說明**：明訂 `clientDataJSON.origin` 可為 web origin 或 `android:apk-key-hash:...`，兩者皆比對租戶 `expected_origin` 允許清單；並說明原生 App origin 的允許清單來源是租戶登錄的 App 簽章指紋（指向本文件）。
2. **（選配）錯誤碼細分**：目前 origin/RP ID 不符共用 `403 RP_ID_MISMATCH`。可考慮為 origin 不符另設 `403 ORIGIN_NOT_ALLOWED`，便於購物網站分辨「是網域對不上還是 origin 不在允許清單」。屬體驗優化，非必要。（OB4）
3. **（選配）稽核記錄 origin 型別**：建議在 `registration/result` / `authentication/result` 寫 `audit_log.detail` 時，記錄本次 ceremony 的 origin 與其型別（`WEB` / `NATIVE_APP`）。有助於事後鑑識「某使用者是從網頁還是 App 登入」。`audit_log` 為 JSON `detail` 欄位，**無 schema 變更**即可容納。（OB5）
4. **（選配）在 `registration/result` Response / 裝置列表記錄註冊來源型別**：可在 `bound_devices.attestation_summary`（既有 JSON 欄位）或裝置回應中附註冊當下的 origin 型別，供管理介面顯示「此裝置是在 App 內或網頁註冊」。**無 schema 變更**（用既有 JSON 欄位）。屬管理便利性，非必要。

### 7.3 租戶 App 指紋登錄的「管理介面 API」

原生 App 情境需要一個「租戶登錄/輪替其授權 App 簽章指紋」的管理動作。這屬**營運/管理平面**，與現行 `api-contract.md`（服務購物網站**後端**的 ceremony API）不同層。建議**不塞進 v1 的 ceremony REST 合約**，而是：

- v1 初期可由平台營運方於 onboarding 時**手動**寫入 `tenants`（設定 `expected_origin` 含 app origin + 選項 A/B 的 metadata），與現行「租戶開通、API Key 發放」同屬人工 onboarding 步驟。
- 日後若原生 App 租戶增多，再評估提供自助式管理 API/後台。此範圍與時程列 OB6，待人類確認。

### 7.4 資料模型改動彙整

| 改動 | 是否必要 | schema 變更 | 對應 |
|---|---|---|---|
| `expected_origin` 允許清單納入 app origin | 原生 App 情境必要 | 無（既有欄位已支援 JSON 陣列） | 5.3 |
| 租戶 App 授權 metadata（選項 A 加欄位 / 選項 B 加表） | 建議（管理/稽核用） | 有（二選一，待確認） | 5.3 / OB3 |
| `audit_log.detail` 記 origin 型別 | 選配 | 無（既有 JSON 欄位） | 7.2 / OB5 |

---

## 8. 本文件補充決策清單（待人類確認）

> 以下皆為 CLAUDE.md / 既有合約未涵蓋、由本文件先行提出、**尚未拍板**的項目。經人類確認後，OB1 / OB3 等會影響 CLAUDE.md「已確認決策」或 db-schema「六張核心表」敘述者，須回填對應文件。

| 編號 | 決策提案 | 理由 | 影響面 |
|---|---|---|---|
| **OB1** | v1 範圍：**瀏覽器情境為強制基準**；**原生 App 情境於 provider/資料模型層一併支援，但正式對租戶啟用採 opt-in 且是否納入 v1 承諾待確認**。 | provider 動態取 origin 本就必須同時處理兩路徑，一次做滿避免返工；但原生 App 的實機/DAL/OEM 驗證與租戶 onboarding 有額外成本，宜與上線時程脫鉤。 | CLAUDE.md 需補「存取情境（瀏覽器/原生 App）」的範圍定義（情境 A 目前未細分） |
| **OB2** | provider 端**預設不**自行抓取 `assetlinks.json` 做 App↔網域驗證，改由**伺服器 `expected_origin` 允許清單**做唯一不可繞過的把關；provider 端 DAL 驗證列為選配深度防禦。 | 伺服器已是 origin 最終權威；provider 端抓 DAL 會引入網路相依與離線問題。 | provider 實作範圍 |
| **OB3** | 租戶 App 授權 metadata 保存方式：**選項 A（`tenants` 加 `authorized_app_bindings` JSON 欄位）** 或 **選項 B（新增 `tenant_app_bindings` 表）**。本文件傾向選項 A。 | 選項 A 不動「六張核心表」數量、v1 規模足夠；選項 B 較正規但牴觸 CLAUDE.md 六表敘述。 | db-schema.md（新增欄位或表）、CLAUDE.md 資料庫段（若選 B） |
| **OB4** | 是否為 origin 不符新增 `403 ORIGIN_NOT_ALLOWED`（與 `RP_ID_MISMATCH` 區分）。 | 便於購物網站分辨錯因；非安全必要。 | api-contract.md §1.4 錯誤碼表 |
| **OB5** | 是否於 `audit_log.detail` 記錄每次 ceremony 的 origin 與型別（WEB/NATIVE_APP）。 | 事後鑑識登入來源；用既有 JSON 欄位、無 schema 變更。 | api-contract.md §2.2/§3.2、稽核實作 |
| **OB6** | 租戶「登錄/輪替授權 App 指紋」在 v1 採**人工 onboarding**，不納入 v1 ceremony REST 合約；自助管理 API 延後評估。 | 中小規模、原生 App 租戶初期少，人工足夠。 | 營運流程、日後管理平面規劃 |

---

## 9. 交接與後續行動

**待人類確認（本文件無法自行拍板者）：**

- OB1 範圍問題（**最關鍵**）：v1 是否對租戶開放原生 App 登入，或僅瀏覽器？其餘 OB2–OB6 多依賴 OB1 的答案。
- OB3 資料模型作法（選項 A / B），因涉及是否突破「六張核心表」，需人類定調後才回填 db-schema.md / CLAUDE.md。

**確認後可交接 dev-engineer（不阻塞、可先動工的部分）：**

- 無論 OB1 結論為何，provider **都必須**把寫死的 `PocConfig.ORIGIN` 換成第 6 節的動態 origin 解析（至少完成瀏覽器路徑 + 拒絕冒充邏輯）。此項可先行實作，屬正向流程接線、模擬器可驗、與硬體 attestation 無關。
- 若 OB1 確認要支援原生 App，再補：app origin 推導、`expected_origin` allowlist 納入 app origin、選項 A/B 的 metadata 儲存、與（實機取得後）DAL/OEM 實機驗證（對齊 PoC item 10「pending 實機」）。

**確認後需回填的文件：**

- CLAUDE.md：於「已確認的關鍵架構決策」補一列「WebAuthn origin 綁定 / 存取情境（瀏覽器 vs 原生 App）」，指向本文件（依 OB1 結論）。
- `api-contract.md`：依 OB4/OB5 決定是否補錯誤碼與稽核欄位；§1.2 補 origin 說明（7.2）。
- `db-schema.md`：依 OB3 決定是否加欄位/表。
