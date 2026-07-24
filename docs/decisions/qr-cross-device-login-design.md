# 桌機瀏覽器 QR 掃碼跨裝置登入 — 技術設計方案

> **狀態（2026-07-24 更新）：第 9 節 S1–S7 已由專案擁有者全數拍板，決策已回填 `CLAUDE.md` / `docs/api-contract.md`（§3.4）/ `docs/db-schema.md`（第 11 節）/ `docs/origin-binding.md`（OB7）與四份採用廠商文件，待 devops/dev/qa 依第 10 節交接實作。** 拍板結論與設計原建議的**兩點差異**務必留意：(1) **S2 proximity 政策＝擁有者選定「只警示不阻擋」**（非本文原建議的 strict——本文 5.2.3/5.2.4 以 strict 為前提的殘餘風險評估因此改變，見 CLAUDE.md「桌機 QR 掃碼跨裝置登入（情境三）決策定案」段落標示的風險敞口）；(2) **新增 S7 落地機制「session JWT `amr` 帶 `xdev`」**（本文原無此細節，為回填時新補的設計，權威定義見 `docs/api-contract.md` §1.3 / D17）。以下第 9 節建議欄保留原始建議供對照，實際拍板以上述為準。
>
> **（原始狀態，已過時）設計方案，待專案擁有者最終確認後才進入實作。**
> 本文是「先設計、後實作」節奏下的**設計文件**（比照先前「金鑰持久化 / 租戶開通 CLI」那次）。文件未動 `CLAUDE.md`、未動 `docs/api-contract.md` / `docs/db-schema.md` / `docs/origin-binding.md`、未寫任何 production code，也未動 Android manifest。所有需擁有者拍板的點集中列於 [第 9 節](#9-需擁有者拍板的項目彙整sign-off)；其餘為 systems-analyst 已做的具體設計決定（標示為「設計決定」），dev-engineer 可據以實作。
>
> 撰寫：systems-analyst｜日期：2026-07-24｜對照原始碼版本：`main` @ 5388ca4
> 前置脈絡：`docs/decisions/desktop-browser-support-evaluation.md`（方案 A/B/C 評估，方案 B hybrid 前提查證不成立）。**本文不重評估桌機支援，而是把擁有者選定的「參考偉康 Transaction Confirmation 精神、套用在既有 FIDO2/WebAuthn 憑證上」方向設計成可實作方案。**

---

## 0. 一頁摘要

- **要做的事**：桌機瀏覽器顯示 QR → 手機 App 被 QR 帶出的 deep link 喚起 → App 向 `fido-server` 換回這個登入 session 的**權威 rpId/challenge** → 指紋辨識 → 用**既有已註冊私鑰**做一次**真正的 WebAuthn assertion**（不是回報裸旗標）→ 送回 `fido-server` → 桌機輪詢確認成功、建立購物網站 session。
- **借用偉康 Transaction Confirmation 的核心**：手機簽的是「與伺服器發的 challenge 密碼學綁定」的內容，伺服器驗簽章、不信任 boolean。這一層我方**用既有 WebAuthn assertion 機制即可達成**，不需改去實作 UAF 協定訊息格式。
- **本文最關鍵、也最誠實的一段結論（見 [第 5 節](#5-qr-中繼--釣魚攻擊防禦設計最關鍵)）**：密碼學綁定能擋掉裸旗標、challenge 替換、重放、跨服務混淆；**但它擋不住「即時人為中繼」**——攻擊者把真 QR 轉貼到釣魚頁誘victim 掃碼並確認。這是所有「QR + 網路回報」自訂協定的本質弱點（WebAuthn 官方 hybrid 靠 BLE 近距證明解決，我方走不了那條路）。本文選定的防禦組合把殘餘風險壓到「攻擊者與受害者共用同一網路出口 IP，且受害者對正確服務名稱仍點下確認」的窄帶，**此殘餘風險需擁有者明確簽核**。

---

## 1. 名詞與新增元件總覽

| 名詞 | 意義 |
|---|---|
| **xdev session（跨裝置登入 session）** | 本設計的新核心物件：一次「桌機發起、手機確認」的登入嘗試。以不透明高熵 `xdevId` 識別，有自己的狀態機與 TTL，內含一個標準 `auth_challenges` 列。 |
| `xdevId` | xdev session 的對外不透明識別（≥256-bit 亂數 base64url）。**QR 內唯一承載的東西**。同時是手機呼叫 fido-server 的能力憑證（capability / bearer）。 |
| **確認碼（verification code）** | 伺服器為每個 xdev session 產生的短碼（見 5.4），桌機 QR 頁與手機確認畫面各顯示一份，供使用者比對（其防護力有限，見 5.4）。 |

新增元件一覽（實作範圍，非本文動工）：

- `fido-server`：新增 `cross_device_sessions` 表與 JPA 實體、`CrossDeviceLoginController` + service、4 個新端點（見第 6 節）、新錯誤碼。assertion 密碼學驗證**重用** `AuthenticationService.verifyResult` 核心。
- `android-credential-provider`：新增 `CrossDeviceLoginActivity`（deep-link 進入，**非** Credential Manager PendingIntent 喚起）、App 端 HTTPS client（新能力）、憑證選擇 UI、確認 UI。assertion 簽章、authenticatorData/clientData 組裝、UV 閘門**重用** `GetPasskeyActivity` 既有邏輯。
- `shopping-site-reference`：新增 cross-device 的 start / poll 代理端點。CONFIRMED 收尾**重用** `AuthenticationProxyController.result` 的 JWT 驗證 + `ShopSessionService.createSession` + cookie 邏輯。

---

## 2. 端對端流程（時序）

```
桌機瀏覽器            購物網站後端            fido-server                手機 App
   |                     |                       |                          |
   |  開啟登入頁          |                       |                          |
   |  按「手機掃碼登入」  |                       |                          |
   |-- POST start ------->|                       |                          |
   |  (帶桌機 client IP)  |-- POST cross-device/  |                          |
   |                     |    sessions ---------->|  建 auth_challenges       |
   |                     |  (X-API-Key,          |  建 cross_device_sessions |
   |                     |   desktopClientIp)    |  status=PENDING           |
   |                     |<-- xdevId, qrUrl, ----|  存 desktop_ip, code      |
   |                     |    code, expiresIn    |                          |
   |<-- qrUrl, code, ----|                       |                          |
   |    pollHandle(cookie)|                       |                          |
   |  顯示 QR + 確認碼    |                       |                          |
   |                     |                       |                          |
   |  ......輪詢中......  |                       |     使用者拿手機掃 QR     |
   |                     |                       |<== deep link 喚起 App ====|
   |                     |                       |   GET/claim sessions/    |
   |                     |                       |<---- {xdevId} -----------|  (只帶 xdevId)
   |                     |                       |  PENDING->SCANNED         |
   |                     |                       |-- rpId, challenge, ----->|  權威 context
   |                     |                       |   code, tenantName        |  由伺服器決定
   |                     |                       |                          |  比對本機憑證 rpId
   |                     |                       |                          |  顯示確認畫面+code
   |                     |                       |                          |  使用者確認 + 指紋
   |                     |                       |                          |  用既有私鑰簽 assertion
   |                     |                       |<-- POST sessions/{id}/---|  (帶 assertion)
   |                     |                       |    result                |
   |                     |                       |  重用 assertion 驗證       |
   |                     |                       |  近距/proximity 檢查       |
   |                     |                       |  SCANNED->CONFIRMED       |
   |                     |                       |  簽 session JWT 暫存       |
   |                     |                       |-- 成功 ----------------->|  手機顯示「已確認」
   |-- GET poll -------->|                       |                          |
   |  (帶 pollHandle)    |-- GET sessions/{id}/  |                          |
   |                     |    status ----------->|  CONFIRMED + JWT          |
   |                     |<-- CONFIRMED, JWT ----|  status->CONSUMED         |
   |                     |  驗 JWT(不信 boolean) |                          |
   |                     |  建 SHOP_SESSION      |                          |
   |<-- 登入成功 + cookie-|                       |                          |
```

---

## 3. QR code 內容與 session 建立（任務 1）

### 3.1 QR 只承載不透明 `xdevId`（設計決定）

QR 編碼一個**已驗證的 Android App Link**，payload 只有 `xdevId`：

```
https://<fido-app-link-host>/x/<xdevId>
```

**設計決定與理由：**

1. **QR 內不放 rpId、不放 challenge、不放任何使用者資訊。** 所有權威資訊由 App 事後用 `xdevId` 向伺服器換回（見任務 3 origin 信任）。QR 若夾帶 rpId/origin，等於讓 QR 自行宣稱來源——正是 `docs/origin-binding.md` 第 1 節禁止的「呼叫方自行宣稱 origin」。
2. **用「已驗證 App Link」而非自訂 scheme（`fidoauth://`）。** 自訂 scheme 可被任何 App 註冊攔截，`xdevId` 屬 bearer 能力值，被別的 App 攔到即可搶登入。App Link 由 `<fido-app-link-host>` 網域的 `assetlinks.json` 綁定本 provider App 簽章指紋，OS 保證只有本 App 會被喚起。`<fido-app-link-host>` 是**平台營運方**自有網域（非租戶網域），營運方可控其 `assetlinks.json`。
3. **App 忽略 URL 內的 host、只取 `xdevId`。** 本 App 是單一營運方 App、服務多租戶，`fido-server` base URL 是 App 內建設定值。App 從 deep link 只解析 `xdevId`，用內建的 fido-server base 發請求，杜絕 QR 竄改 host 把 App 導向惡意伺服器。
4. **在無 App 裝置上的降級**：`https://<fido-app-link-host>/x/<xdevId>` 若在未安裝 App 的裝置被開啟，該 URL 由營運方伺服器回一個「請安裝 App / 本連結需在手機 App 開啟」的靜態頁，不洩漏任何 session 內容。

### 3.2 session 建立與 challenge 時效（設計決定，但 TTL 需擁有者確認 → S6）

`fido-server` 收到 shopping-site 後端的 create 請求後：

1. 產生 32-byte 亂數 challenge，寫入 **`auth_challenges`**（`ceremony_type='AUTHENTICATION'`、`user_ref_id=NULL` usernameless）——**沿用既有 challenge 機制與資料表**，讓後續 assertion 驗證能重用既有程式碼路徑。
2. 產生 `xdevId`（≥256-bit 亂數）與確認碼，寫入新表 **`cross_device_sessions`**（`status='PENDING'`、`desktop_ip`、`expires_at`、`challenge_pk` 指向步驟 1 的列）。
3. 回傳 `xdevId`、`qrUrl`、確認碼、`expiresIn`。

**TTL 設計（deviation，需 S6 確認）**：現有 `auth_challenges` 為 60 秒，適用同裝置「瀏覽器立即 create/get」的即時 ceremony。跨裝置多了「拿起手機→掃碼→看確認畫面→指紋」數個人為步驟，60 秒偏緊。**設計建議：xdev session TTL 預設 120 秒（設定值），並把該 ceremony 對應的 `auth_challenges.expires_at` 一併設為 120 秒**（僅此 cross-device ceremony type 偏離 60 秒預設，同裝置流程不受影響）。理由：人為多步驟；120 秒與 session JWT 效期同量級、仍屬短時效。**此偏離需擁有者點頭（S6）**，因為它動到「Challenge 時效 60 秒」這條既有決策的適用範圍（不是推翻，是為新 ceremony type 加一個放寬值）。

### 3.3 為何用新表 `cross_device_sessions` 而非塞進 `auth_challenges`（設計決定）

`auth_challenges` 是「一次性 challenge」的精簡表，沒有狀態機（只有 PENDING/CONSUMED/EXPIRED）、沒有雙方 IP、沒有確認碼、TTL 慣例是 60 秒。cross-device 需要：多態狀態機（PENDING→SCANNED→CONFIRMED→CONSUMED，另有 DENIED/EXPIRED）、桌機/手機兩個 IP、確認碼、暫存簽發的 JWT/jti、較長 TTL。硬塞進 `auth_challenges` 會污染同裝置流程的語意。**新表包住既有 challenge 列（1:1 外鍵），既隔離新概念、又讓 assertion 密碼學驗證能重用既有以 `ceremony_id` 為入口的邏輯。** 這會使核心表由八張增為九張，屬 CLAUDE.md / db-schema.md 變更，需 S4 確認。

---

## 4. 手機 App 端新入口 `CrossDeviceLoginActivity`（任務 2）

### 4.1 與既有兩個 Activity 的本質差異

| | `CreatePasskeyActivity` / `GetPasskeyActivity` | **`CrossDeviceLoginActivity`（新）** |
|---|---|---|
| 由誰喚起 | 系統 Credential Manager 以 **PendingIntent 隱式**啟動 | **deep link（App Link）** 由使用者掃 QR 觸發 |
| 是否有 `CallingAppInfo` | 有（瀏覽器/原生 App 呼叫方） | **無**（不是 Credential Manager 流程） |
| origin 來源 | `OriginResolver` 從 `CallingAppInfo` 動態解析（見 origin-binding.md 第 6 節） | **伺服器用 `xdevId` 換回的權威 rpId**（見任務 3） |
| 結果如何回傳 | `PendingIntentHandler.setGetCredentialResponse` 交還系統 | **App 自己 HTTPS POST 給 fido-server** |
| App 是否直連 fido-server | 否（結果經瀏覽器→shop→server） | **是（全新能力）** |

### 4.2 `CrossDeviceLoginActivity` 要做的事（流程）

1. **解析 deep link**：從 intent data 取 `xdevId`（忽略 host，見 3.1）。格式不符 → 顯示錯誤並結束。
2. **向 fido-server claim session**（新端點 `POST /api/v1/authentication/cross-device/sessions/{xdevId}/claim`）：伺服器驗 `xdevId` 存在且 `status=PENDING` 且未過期，轉 `PENDING→SCANNED`，回傳**權威 context**：`rpId`、`tenantDisplayName`、`challenge`(base64url)、確認碼、`expiresAt`。**這是 App 取得 rpId/origin 的唯一權威來源**（任務 3）。
3. **比對本機憑證**：以回傳的 `rpId` 查 `LocalCredentialStore` 找出本機為該 rpId 註冊的 active 憑證（usernameless / discoverable 選取）。
   - 0 筆 → 顯示「此裝置未註冊 <tenantDisplayName>」，向伺服器回報放棄（`status→DENIED`，audit），結束。
   - 多筆 → 顯示帳號/憑證選擇器（重用 rpId→credential 對應；沿用現有 discoverable 選取精神）。
4. **顯示確認畫面（Transaction Confirmation UI）**：顯示 `已收到來自電腦端的登入請求`、`網站：<tenantDisplayName>（<rpId>）`、`電腦端確認碼：<code>`、`時間：<...>`，兩顆按鈕「確認登入」/「不是我，取消」。取消 → `status→DENIED` + audit。
5. **UV 閘門**：使用者按確認後跑 `BiometricPrompt`（UV=required）——**直接重用 `GetPasskeyActivity.requireUserVerification`**。
6. **簽 assertion**：UV 成功後——**重用 `GetPasskeyActivity.performAssertion` 的簽章核心**：
   - `AuthenticatorDataBuilder.buildForAssertion(rpId, userVerified=true, signCount=newCount)`
   - `ClientDataBuilder.build(type="webauthn.get", challenge, origin = "https://" + rpId)`（origin 見 4.3）
   - `ClientDataBuilder.resolveClientDataHash(callerSuppliedClientDataHash = null, selfBuiltClientDataJson)`——**cross-device 沒有瀏覽器 client 提供 hash，故走「自建 clientDataJSON 重新雜湊」路徑**（與 origin-binding.md 原生 App opt-in 情境同一分支，`clientDataHash` 為 null 時的既有行為）。
   - `Signature.getInstance("SHA256withECDSA")` 對 `authenticatorData || clientDataHash` 簽章；`LocalCredentialStore.setSignCount(...)` 遞增。
   - `buildAssertionResponseJson(...)` 組出與 §3.2 result 相同結構的 assertion JSON。
7. **送回 fido-server**（`POST /api/v1/authentication/cross-device/sessions/{xdevId}/result`），body = 上面的 assertion JSON。伺服器驗證通過 → `SCANNED→CONFIRMED`。App 依回應顯示「已確認，請回到電腦」或失敗原因。

### 4.3 可重用 vs 全新（給 dev-engineer 的清單）

**直接重用 `GetPasskeyActivity` 既有邏輯**：
- `requireUserVerification(...)` 生物辨識閘門（原封）。
- `performAssertion(...)` 的 Keystore 取私鑰 + 簽章核心（`SHA256withECDSA`、`update(authenticatorData)`、`update(clientDataHash)`）。
- `AuthenticatorDataBuilder.buildForAssertion(...)`、`ClientDataBuilder.build(...)` + `resolveClientDataHash(...)`、`buildAssertionResponseJson(...)`。
- `LocalCredentialStore`：`getRpId`、`aliasFor`、`getSignCount`/`setSignCount`、以 rpId 反查 active 憑證。

**全新（cross-device 專屬）**：
- deep link intent-filter 與 `xdevId` 解析。
- **App 端 HTTPS client 直連 fido-server**（現況 App 完全不直連 fido-server；需 base URL 設定、TLS、`network_security_config` 調整、逾時/錯誤處理）。這是 App 最大的新增能力。
- 用 `xdevId` claim/submit 的兩次伺服器往返與其狀態處理。
- rpId→本機憑證選擇器 UI、Transaction Confirmation 確認 UI。
- **origin 不再來自 `CallingAppInfo`/`OriginResolver`**，而是伺服器回傳 rpId 推導的 `https://<rpId>`（見任務 3）。`OriginResolver` 的瀏覽器 allowlist / apk-key-hash 路徑在此情境**不適用**。

---

## 5. `clientDataJSON` / origin 的信任（任務 3）+ QR 中繼防禦（任務 4）

### 5.1 origin 從哪裡來、如何確保沒被竄改（任務 3）

**設計決定：origin 由伺服器用 `xdevId` 權威決定，App 與 QR 都不得自行宣稱。**

- QR 只帶不透明 `xdevId`（3.1）。
- App claim 時，伺服器由 `xdevId → cross_device_sessions.tenant_id → tenants.rp_id / expected_origin` 反查，把**該 session 真正對應的 rpId** 回給 App。
- App 據此 rpId 簽 `clientDataJSON.origin = https://<rpId>`（更精確地：伺服器可直接回傳要簽的 `origin` 字串，取自 `tenants.expected_origin`，App 照簽；避免 App 自行拼字串與 tenant 多 origin 的歧義）。
- 最終 `fido-server` 在 result 端仍用**既有** `OriginValidator` 把 `clientDataJSON.origin` 比對該租戶允許清單（此情境恆為伺服器自己給的值，必過）。

這與 `docs/origin-binding.md` 的既有原則一致：**origin 動態、經伺服器驗證取得，不寫死、不由呼叫方宣稱**。差別在於：同裝置情境的 origin 由 OS/瀏覽器擔保；cross-device 情境**沒有瀏覽器擔保這一層**，改由「`xdevId` → 租戶」的伺服器綁定取代「呼叫方身分 → origin」。

**必須誠實點出的新攻擊面**：標準 WebAuthn 的 `origin` 之所以能防釣魚，是因為**瀏覽器保證使用者當下真的在該網域**。cross-device 情境裡，簽 origin 的手機**根本沒看到桌機在哪個網域**——它只是照著伺服器（依 `xdevId`）給的 rpId 簽。因此 origin 欄位在此**退化為「租戶識別」，不再帶有「使用者確實在該網域」的防釣魚語意**。防釣魚的責任因此整個轉移到第 5.2 節的中繼防禦，而非 origin 本身。這是本方案與同裝置 WebAuthn 的**根本安全落差**，需在 S1 明確簽核。

### 5.2 QR 中繼 / 釣魚防禦（任務 4，本設計最關鍵）

#### 5.2.1 威脅模型：即時人為中繼

攻擊者用自己的瀏覽器對**真站**發起 xdev session，取得真 QR，貼到一個釣魚頁誘使受害者掃碼；受害者指紋確認後，assertion 綁定的是**攻擊者的 session**，攻擊者的桌機因此以受害者身分登入。

#### 5.2.2 誠實評估：哪些手法有效、哪些其實無效

- **密碼學綁定（偉康 Transaction Confirmation 核心）**：手機簽真 assertion、伺服器驗簽章而非信任 boolean。**有效對付**裸旗標、challenge 替換/竄改、assertion 重放到別的 session、跨服務混淆。**無效對付**即時人為中繼（攻擊者原封轉貼真 QR，challenge 對受害者而言就是「真的」）。→ **必要，但不足。**
- **確認碼「畫面比對」（number/emoji matching）**：在 push-MFA 情境有效，是因為號碼在**受害者看不到的攻擊者螢幕上**。但 QR 是攻擊者主動遞給受害者掃的，攻擊者**能把正確確認碼一併印在釣魚頁上**。→ 對「攻擊者完全掌控受害者所見畫面」的中繼**基本無效**，只有透明化/竄改可見性的次要價值（見 5.4）。**誠實標示為輔助，不當主力。**
- **同網路出口 IP（proximity / 近距代理）**：比較「發起+輪詢的桌機公網 IP」與「提交 assertion 的手機公網 IP」是否一致。**遠端攻擊者**（與受害者不同地點）中繼時，兩者出口 IP 不同 → 可攔下。這是我方能取得、最接近 hybrid「BLE 近距證明」的替代控制，**是唯一真正咬得住即時遠端中繼的手段**。代價是誤判（手機走行動網路、桌機走 Wi-Fi；企業/校園多重出口 NAT）。→ **選為主力防禦。**
- **短 TTL + 一次性 + 嚴格狀態機**：把中繼壓成必須「即時」完成，縮小窗口。→ **必要輔助。**

#### 5.2.3 選定的防禦組合（設計決定）

本專案採以下**四層組合**：

1. **【必要】密碼學 Transaction Confirmation 綁定**：手機做真 WebAuthn assertion，簽的 clientData 內嵌伺服器發的 challenge；origin/rpId 由伺服器依 `xdevId` 權威給定（5.1）。`xdevId` 高熵、一次性、不可猜。
2. **【必要】嚴格單向狀態機 + 短 TTL + 一次性**：`PENDING→SCANNED→CONFIRMED→CONSUMED`，每個轉移單向不可逆；另有 `DENIED`（使用者取消/無憑證）、`EXPIRED`。TTL 120 秒（S6）。`claim` 只在 `PENDING` 成功、`result` 只在 `SCANNED` 成功、poll 取 JWT 只在 `CONFIRMED` 成功且立即轉 `CONSUMED`（JWT 只能被領一次）。
3. **【必要】手機 Transaction Confirmation 確認畫面**：顯示伺服器權威 `tenantDisplayName`/`rpId`、確認碼、時間，強制使用者顯式「確認 / 取消」。讓警覺使用者能攔下「服務名稱不對」的中繼、並提供「不是我」的明確拒絕出口（`DENIED` + audit）。
4. **【主力】同出口 IP proximity 檢查**：`result` 端比對「shop 後端於 start 時轉來的桌機 client IP」與「手機直連 fido-server 的來源 IP」。**預設 strict：不一致即拒（`422 XDEV_PROXIMITY_MISMATCH`）+ audit。** 提供**每租戶**設定放寬為「warn + 顯示」（不阻擋但於確認畫面/audit 標記異常）以因應誤判率高的使用者族群。strict vs relaxed 預設值需 S2 拍板（本文建議 strict 為預設）。

> **關於桌機 IP 怎麼來**：start 由 shop 後端 server-to-server 呼叫 fido-server，fido-server 看到的來源是 shop 後端 IP，**不是桌機**。故 shop 後端須在 start 與 poll 請求中把「桌機瀏覽器的真實 client IP」（其自身請求的 `remoteAddr` / `X-Forwarded-For`）以欄位 `desktopClientIp` 轉給 fido-server。手機則是**直連** fido-server，來源 IP 即真實手機 IP。信任假設：shop 後端誠實轉發 client IP（shop 後端本就是租戶自家可信元件）。

#### 5.2.4 殘餘風險與簽核需求（誠實結論）

即使四層全上，**以下殘餘風險仍在**：

- **同網路出口的即時中繼**：攻擊者與受害者在同一 NAT 出口（同一公司 Wi-Fi、同一公共熱點，或攻擊者透過受害者裝置的 proxy/惡意程式借用其出口），proximity 檢查會誤判為「同一台」而放行。
- **使用者對「正確服務名稱」仍點確認**：中繼目標與受害者以為要登入的是同一個服務時，確認畫面顯示的正是受害者預期的服務名，社交工程下使用者仍會確認。

**因此 cross-device QR 登入在本質上比同裝置 WebAuthn 的防釣魚保證弱一級。** 這不是實作瑕疵，是「QR + 網路回報、無 BLE 近距」這條路線的固有上限。**必須請擁有者對此殘餘風險簽核（S1）**，並建議（S7）：把 QR 掃碼登入定位為**便利性選項**，高風險動作（改密碼、金流、改綁定）仍要求同裝置強驗證；若擁有者的實際使用者以桌機為主且重視防釣魚，`docs/decisions/desktop-browser-support-evaluation.md` 的**方案 C（桌機本機 Windows Hello 註冊）才是防釣魚更強的桌機路徑**，兩者可並存（QR 求便利、方案 C 求安全）。

---

## 6. 桌機端輪詢與新端點（任務 5）

### 6.1 `fido-server` 新增端點（手機直連 + shop 後端呼叫兩類呼叫方）

> **新的呼叫方類別（需 S5 確認）**：`api-contract.md` 前言載明「所有端點呼叫方是購物網站後端、以 X-API-Key 表明租戶」。cross-device 打破此前提：**手機 App 直連** fido-server 的 claim/result 端點，這兩個端點**不帶 X-API-Key**，改以**不透明 `xdevId` 作 capability 認證**，租戶由 `xdevId` 反查。這是刻意的新增呼叫方類別，須在 api-contract.md 明訂並經擁有者拍板。

| # | Method | Path | 呼叫方 | 認證 | 作用 |
|---|---|---|---|---|---|
| A | POST | `/api/v1/authentication/cross-device/sessions` | **shop 後端** | X-API-Key + `desktopClientIp` | 建 session，回 `xdevId`/`qrUrl`/`code`/`expiresIn` |
| B | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/claim` | **手機 App** | `xdevId`（capability） | `PENDING→SCANNED`，回權威 `rpId`/`challenge`/`code`/`tenantDisplayName` |
| C | POST | `/api/v1/authentication/cross-device/sessions/{xdevId}/result` | **手機 App** | `xdevId`（capability） | 驗 assertion + proximity，`SCANNED→CONFIRMED` |
| D | GET | `/api/v1/authentication/cross-device/sessions/{xdevId}/status` | **shop 後端** | X-API-Key + `desktopClientIp` | 回狀態；`CONFIRMED` 時回 session JWT 並 `→CONSUMED` |

- **端點 C 的 assertion 驗證重用既有邏輯**：以 `xdevId` 反查其 `challenge_pk` 對應的 `ceremony_id`，交由**既有** `AuthenticationService.verifyResult` 做 `webauthn.get` 型別檢查、challenge 比對、rpIdHash 比對、origin 允許清單比對、UV flag、公鑰驗簽、sign counter 檢查（含倒退自動撤銷）。cross-device 只在其外層**加**兩件事：狀態機轉移、proximity 檢查。**密碼學驗證零重寫。**
- **端點 D 的 JWT** 與 §3.2 同一把 session JWT（同 claims、同 ES256、同 JWKS）。**JWT 只在 status=CONFIRMED 時回一次，回後立即 `CONSUMED`**，避免同一結果被領兩次。

### 6.2 `shopping-site-reference` 新增代理端點

| Method | Path | 作用 |
|---|---|---|
| POST | `/shop/api/fido/authentication/cross-device/start` | 需 CSRF token（沿用 `CsrfCookieFilter`）。取桌機 client IP → 呼叫 fido-server 端點 A → 回 `qrUrl`/`code`/`expiresIn`，並設一個 httpOnly `XDEV_POLL` cookie 綁定本次桌機瀏覽器與 `xdevId`（見 6.4）。 |
| GET | `/shop/api/fido/authentication/cross-device/poll` | 讀 `XDEV_POLL` cookie 取 `xdevId` → 帶桌機 client IP 呼叫 fido-server 端點 D → 依狀態回應（見 6.3）。 |

### 6.3 輪詢頻率、逾時、成功收尾

- **頻率（設計決定）**：桌機每 **2–3 秒** short-poll 一次。每租戶既有 **100 TPS** 速率限制（api-contract D3）已足以吸收；毋須新機制。**可選優化**：伺服器端 long-poll（hold ≤25 秒）降低請求數，列為 dev 可選，非必要。
- **逾時**：session `expires_at` 到期，端點 D 回 `EXPIRED`；桌機顯示「QR 已過期，請重新產生」並停止輪詢。DENIED（使用者在手機取消 / 無憑證）時 poll 回 `DENIED`，桌機顯示對應訊息。
- **成功收尾（重用既有 result 邏輯）**：當 poll 得到 `CONFIRMED` + JWT，shop 後端**比照 `AuthenticationProxyController.result` 的收尾**：
  1. **不信任任何 `confirmed`/`verified` 布林**，只信 `FidoSessionJwtValidator.validate(jwt)` 驗簽通過的結果（沿用「只信驗過簽的 JWT」信任邊界）。
  2. `ShopSessionService.createSession(externalUserId, deviceId, credentialId)`（取自 JWT claims）。
  3. 設 `SHOP_SESSION` cookie（httpOnly、`secure` 沿用 `shop.session.cookie.secure`、SameSite=Lax）。
  4. 回桌機「登入成功」。
  → **不需要新的收尾端點**；收尾邏輯與現有 `/result` 幾乎相同，差別只在「觸發點」是 poll 命中 CONFIRMED 而非前端直接 POST result。建議把 JWT→session 收尾抽成 `ShopLoginFinalizer` 共用方法供兩處呼叫（dev 實作細節）。

### 6.4 輪詢結果只能交回發起的桌機（防他人竊取 JWT）

`XDEV_POLL` cookie 內含一個與 `xdevId` 綁定的高熵 poll secret（或 shop 後端維護 `poll_secret → xdevId` 的伺服器端對映）。poll 端點只認 cookie，不接受用查詢參數帶 `xdevId`——確保「拿到 QR 或猜到 `xdevId` 的第三方」無法向 shop 輪詢別人的 CONFIRMED 結果、竊取其 session JWT。CONFIRMED 的 JWT 因此只會交回**發起該 session 的同一個桌機瀏覽器**。

---

## 7. 資料模型變更（任務 6，列範圍不改檔）

新增第九張核心表 `cross_device_sessions`（需 S4）。**欄位為設計提案，最終權威定義待回填 `docs/db-schema.md` 第 11 節**：

| 欄位 | 型別（提案） | 說明 |
|---|---|---|
| `xdev_pk` | BIGINT IDENTITY | 內部 PK |
| `xdev_id` | NVARCHAR(64) 或 VARBINARY | 對外不透明 capability 識別，唯一（≥256-bit 亂數 base64url） |
| `tenant_id` | BIGINT FK→tenants | 租戶 |
| `challenge_pk` | BIGINT FK→auth_challenges | 1:1 包住的既有 challenge 列 |
| `status` | NVARCHAR(20) | CHECK IN ('PENDING','SCANNED','CONFIRMED','CONSUMED','DENIED','EXPIRED') |
| `verification_code` | NVARCHAR(16) | 確認碼 |
| `desktop_ip` | NVARCHAR(45) | shop 後端轉來的桌機 client IP（proximity 基準） |
| `phone_ip` | NVARCHAR(45) | 手機直連來源 IP（result 時寫入） |
| `user_ref_id` | BIGINT FK NULL | 確認成功後由 credential 反查填入 |
| `credential_pk` | BIGINT FK NULL | 本次使用的憑證 |
| `issued_jti` | NVARCHAR(64) NULL | 簽發 session JWT 的 jti（防重領/稽核） |
| `expires_at` / `scanned_at` / `confirmed_at` / `consumed_at` / `created_at` / `updated_at` | DATETIME2(3) | 時效與狀態時間戳（UTC，對齊 DB1/DB8） |

- 鍵/索引：PK `xdev_pk`；UNIQUE `xdev_id`；INDEX (`tenant_id`,`status`)；INDEX `expires_at`（清理）。
- 清理：比照 `auth_challenges`（DB11），SQL Agent Job 定期把逾期 PENDING/SCANNED 標 EXPIRED、每日刪舊列（非稽核來源，稽核走 `audit_log`）。
- 軟刪除不適用（短生命週期組態外的一次性 session）；稽核事件另寫 `audit_log`。

**其他文件預期改動範圍（不在本文執行）**：

- `docs/api-contract.md`：新增 §3.4「跨裝置 QR 登入」四端點（A–D）；`§1.2` 補「手機 App capability 認證」呼叫方類別（S5）；新增錯誤碼 `XDEV_SESSION_NOT_FOUND`(404→改 400 保持防列舉一致，見下)、`XDEV_SESSION_EXPIRED`(400)、`XDEV_SESSION_INVALID_STATE`(409)、`XDEV_PROXIMITY_MISMATCH`(422)、`XDEV_NO_CREDENTIAL_FOR_RP`（手機端本地判定，未必需伺服器碼）；`audit_log.detail` 的 `originType` 擴充 `CROSS_DEVICE_QR`（沿用既有 JSON 欄位，無 schema 變更，比照 D13）。
  - 防列舉一致性：`xdevId` 查無時回應形態應與既有 D7 精神一致（不用 404 洩漏 capability 存在性）；但 `xdevId` 是高熵一次性值、非帳號/裝置識別，列舉風險本就極低，dev 實作時對齊即可。
- `docs/origin-binding.md`：新增一條 **OB7**（cross-device origin 信任模型）：origin 由伺服器依不透明 `xdevId` 權威給定、非瀏覽器擔保、非呼叫方宣稱；防釣魚改由第 5.2 節的 proximity + 確認畫面承擔，origin 欄位在此退化為租戶識別。明載此情境防釣魚弱於同裝置、殘餘風險已由擁有者簽核（S1）。
- `CLAUDE.md`：新增「情境三（桌機瀏覽器 + 手機漫遊確認器）」於架構情境敘述；決策表新增對應列；`非獨立 APP 跳轉` 決策加註 cross-device carve-out（見第 8 節）；核心表八→九張。

---

## 8. 對既有架構決策的影響（任務 6）

### 8.1 是否構成新「情境」——是（需 S3）

CLAUDE.md 現定義情境 A 涵蓋「手機瀏覽器」與「原生 App 直呼 Credential Manager」兩種**同裝置**前端。cross-device QR 是**第三種、跨裝置**情境：桌機瀏覽器發起、手機作漫遊確認器。建議命名 **情境三（cross-device QR transaction confirmation）**，明確與已放棄的**情境 B（跨裝置推播）區隔**：

- 情境 B 是自訂**推播/帶外核准**（放棄）。
- 情境三是**pull-based QR + 真 WebAuthn assertion**，且**不使用 WebAuthn hybrid/caBLE**（那條路前提已查證不成立，見評估文件方案 B）。
- 手機端做的是**真簽章**（非裸旗標），這正是與「情境 B 推播核准」在安全性上的關鍵不同。

### 8.2 與 `非獨立 APP 跳轉` 決策的關係——需 carve-out（併入 S3）

CLAUDE.md「FIDO 驗證 APP 角色」決策要求「自訂 `CredentialProviderService`，掛載於系統 Credential Manager（**非獨立 APP 跳轉**、非推播）」。`CrossDeviceLoginActivity` 是**由 deep link 喚起的 App-initiated ceremony**，表面上像「獨立 App 跳轉」。需釐清並請擁有者拍板此 carve-out：

- 該決策原意管的是**同裝置正常 ceremony 不得改走獨立 App**（避免退化成並行認證/管理路徑）。cross-device 情境**手機上根本沒有瀏覽器頁面/原生 App 在呼叫 Credential Manager**（發起方在另一台桌機），Credential Manager 隱式喚起模型在此**物理上不適用**——deep link 是唯一可行喚起方式。
- 因此 `CrossDeviceLoginActivity` 是**新情境下不得不的 App-initiated 入口**，屬明確 carve-out，**不改變同裝置流程**（同裝置仍一律走 Credential Manager PendingIntent）。
- **硬性範圍邊界**（比照 SetupStatusActivity）：此 Activity 只能經**有效的、伺服器發出的 `xdevId` deep link** 進入；只做「claim→確認→簽 assertion→submit」單一 ceremony；**不得**新增裝置列表/撤銷/註冊等任何管理或並行認證 UI（那會違反 `非獨立 APP 跳轉` 原意）。註冊仍只走 Credential Manager。

### 8.3 對「金鑰保護 / 支援裝置」等決策無影響

cross-device 用的是**手機上既有、已通過 TEE/StrongBox 註冊的憑證**做 assertion，不新增任何桌機驗證器、不觸及桌機 attestation 格式問題（那是評估文件方案 A/C 的範疇）。因此本設計**不動搖「強制硬體安全區」核心承諾**、不需支援桌機 attestation 格式、不需碰 `SecurityLevel` 模型。這是本方案相對評估文件方案 A/C 的一個明顯優點：**桌機零註冊、零 attestation 擴充，複用手機既有硬體金鑰**。

---

## 9. 需擁有者拍板的項目彙整（sign-off）

| 編號 | 需拍板事項 | systems-analyst 建議 |
|---|---|---|
| **S1（最重要）** | 接受 cross-device QR 登入**本質上比同裝置 WebAuthn 防釣魚弱**：密碼學綁定擋不住即時人為中繼；殘餘風險 = 同網路出口的即時中繼 + 使用者對正確服務名仍確認。是否接受此殘餘風險上線？ | 可接受，但**建議 S7 配套**（限低風險動作 / 保留同裝置強驗證作高風險路徑）。 |
| **S2** | 同出口 IP proximity 檢查的預設政策：strict（不符即拒）vs relaxed（僅警示）。 | **預設 strict**，提供每租戶放寬設定。 |
| **S3** | 新增「情境三」至 CLAUDE.md，並對 `非獨立 APP 跳轉` 決策加 `CrossDeviceLoginActivity` 的 carve-out（含硬性範圍邊界）。 | 同意新增，carve-out 邊界如 8.2。 |
| **S4** | 新增第九張核心表 `cross_device_sessions`（八→九張）。 | 採新表（理由見 3.3）。 |
| **S5** | 新增「手機 App 直連 fido-server、以 `xdevId` capability 認證、不帶 X-API-Key」的呼叫方類別。 | 必要（多租戶下手機無法得知該打哪個 shop；`xdevId` 反查租戶最乾淨）。 |
| **S6** | cross-device ceremony 的 challenge/session TTL 放寬為 120 秒（僅此 ceremony type，不動同裝置 60 秒）。 | 建議 120 秒。 |
| **S7** | 是否把 QR 登入限縮為便利性 / 低風險動作，高風險動作仍要求同裝置強驗證；是否同時推進評估文件方案 C（桌機 Windows Hello）作為防釣魚更強的桌機路徑。 | 建議是（兩者並存、分場景）。 |

---

## 10. 交接（拍板後）

擁有者對第 9 節拍板後，建議交接順序：

1. **systems-analyst 回填**（拍板後）：`CLAUDE.md`（情境三、carve-out、九張表）、`docs/api-contract.md`（§3.4 端點、呼叫方類別、錯誤碼、originType）、`docs/db-schema.md`（第 11 節 `cross_device_sessions`）、`docs/origin-binding.md`（OB7）。
2. **devops-engineer**：`infra/sql/` 新增 `cross_device_sessions` 建表/索引/清理 Job，LocalDB 重驗含第九張表。
3. **dev-engineer**：fido-server 端點 A–D + JPA 實體 + 重用 `verifyResult` + proximity/狀態機；shopping-site start/poll 代理 + 重用 JWT 收尾；`CrossDeviceLoginActivity` + App HTTPS client（重用 assertion 簽章邏輯）。
4. **qa-engineer**：新增 proximity 反例、中繼情境負向測試、狀態機一次性/重放負向測試、跨行程 E2E（比照現有 `CrossProcessE2EManualRunner`）。

---

## 附錄：與偉康 UAF Transaction Confirmation 的對應關係（澄清「借精神、非實作 UAF」）

| 偉康 UAF Transaction Confirmation 精神 | 本設計在 FIDO2/WebAuthn 上的對應落地 |
|---|---|
| 操作發起處與做生物辨識簽章的裝置可不同 | 桌機發起、手機簽章（本設計核心場景） |
| 手機簽的是與原始 session/挑戰密碼學綁定的內容，非裸旗標 | 手機做真 WebAuthn assertion，簽 `authenticatorData‖SHA256(clientDataJSON)`，clientData 內嵌伺服器發的 challenge |
| 伺服器驗簽章、不信任 boolean | fido-server 重用 `AuthenticationService.verifyResult` 對公鑰驗簽；shop 只信驗過簽的 session JWT，不信 `confirmed` 布林 |
| （UAF 專屬）UAF 協定訊息格式 | **不採用**。我方是 WebAuthn 平台，沿用既有 WebAuthn attestation/assertion 訊息與 `fido_credentials` 憑證基礎設施，不引入 UAF 協定格式 |

> 一句話：**借偉康「簽章與原始挑戰密碼學綁定」的核心精神，落在我方既有 WebAuthn 憑證上；不改去支援 UAF 協定。**
