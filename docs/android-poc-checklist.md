# Android Credential Provider 技術驗證 PoC — 驗證項目清單

- 版本：v1（草案，待人工複核）
- 最後更新：2026-07-22
- 適用架構情境：A（標準 WebAuthn，同裝置）
- 對齊文件：`d:\fido\CLAUDE.md`、`d:\fido\docs\api-contract.md`、`d:\fido\docs\db-schema.md`
- 讀者：dev-engineer（實作 PoC）、qa-engineer（照驗收標準回報）、之後拿到實機的人
- 執行角色：資深工程師（PoC 期程 2 週）

> 本文件把 CLAUDE.md「目前階段」中抽象提到的「10 項驗證項目、#1–5 為關鍵項目」**具體定案**。每一項都標註「模擬器可驗證 / 須實機驗證 / 兩者皆需」，並給出可直接照做的通過/失敗判準。
>
> 凡標記 **【待人工確認】** 者，為推導過程中發現 CLAUDE.md / 既有合約尚未明訂、但會影響 PoC 判定或後續架構的點，集中列於[附錄 A](#附錄-a推導過程中發現需人工確認的點) 與[附錄 B](#附錄-bpoc-通過門檻建議待人工確認)，**不由本文件片面拍板為新架構決策**。

---

## 目錄

1. [PoC 執行環境與前置條件](#1-poc-執行環境與前置條件)
2. [模擬器 vs 實機：本專案的驗證邊界](#2-模擬器-vs-實機本專案的驗證邊界)
3. [10 項驗證清單總覽](#3-10-項驗證清單總覽)
4. [關鍵項目排序邏輯](#4-關鍵項目排序邏輯)
5. [逐項驗證細節與通過/失敗判準](#5-逐項驗證細節與通過失敗判準)
   - [項目 1（關鍵）CredentialProviderService 掛載與系統辨識](#項目-1關鍵credentialproviderservice-掛載與系統辨識)
   - [項目 2（關鍵）硬體金鑰產生：StrongBox 優先、TEE fallback、絕不落軟體](#項目-2關鍵硬體金鑰產生strongbox-優先tee-fallback絕不落軟體)
   - [項目 3（關鍵）android-key attestationObject 產生與 x5c 憑證鏈輸出](#項目-3關鍵android-key-attestationobject-產生與-x5c-憑證鏈輸出)
   - [項目 4（關鍵）attestationChallenge 與 WebAuthn challenge 綁定](#項目-4關鍵attestationchallenge-與-webauthn-challenge-綁定)
   - [項目 5（關鍵）註冊 ceremony 端對端 + 硬體閘門](#項目-5關鍵註冊-ceremony-端對端--硬體閘門)
   - [項目 6 登入 ceremony 端對端（assertion + sign counter）](#項目-6-登入-ceremony-端對端assertion--sign-counter)
   - [項目 7 多裝置註冊與個別撤銷互不干擾](#項目-7-多裝置註冊與個別撤銷互不干擾)
   - [項目 8 Challenge 60 秒時效的 Android 端體驗與重試](#項目-8-challenge-60-秒時效的-android-端體驗與重試)
   - [項目 9 Credential Manager 喚起速度與生物辨識/裝置解鎖 UX](#項目-9-credential-manager-喚起速度與生物辨識裝置解鎖-ux)
   - [項目 10 Android 14 OEM/廠牌客製化相容性風險](#項目-10-android-14-oem廠牌客製化相容性風險)
6. [附錄 A：推導過程中發現需人工確認的點](#附錄-a推導過程中發現需人工確認的點)
7. [附錄 B：PoC 通過門檻建議（待人工確認）](#附錄-bpoc-通過門檻建議待人工確認)

---

## 1. PoC 執行環境與前置條件

- 開發機：已具備 Android SDK（API 34 / Android 14）+ 模擬器；**目前無實體 Android 裝置**（實機測試須待產出 APK 後才有機會取得）。
- 後端：`fido-server`（Spring Boot），本機或內網啟動，供 PoC 打真正的 REST 端點。伺服器 attestation 密碼學驗證邏輯已實作（`RealAttestationStatementVerifier`、`RealAndroidKeyAttestationChainValidator`）。
- **伺服器 attestation 模式開關**：`fido.attestation.mode`（`real` = 完整密碼學/硬體閘門，預設；`stub` = 放行以便先驗流程接線）。此開關是模擬器階段能否驗到「happy path 之後半段」的關鍵，見第 2 節。
- 伺服器對 Android 端的**硬性合約**（PoC 必須滿足，否則 100% 失敗，來源：`fido-server` 現行實作）：
  1. attestation 格式必須是 `fmt = "android-key"`（其他格式一律 `422 ATTESTATION_INVALID`）。
  2. `attStmt.sig` 必須是 `authenticatorData || clientDataHash` 在 `x5c[0]`（leaf 憑證）公鑰下的合法簽章。
  3. `x5c[0]` 公鑰必須等於 `authenticatorData` 內的 `credentialPublicKey`。
  4. `x5c` 憑證鏈必須鏈接到內建的 Google Hardware Attestation root。
  5. leaf 憑證 Key Attestation extension（OID `1.3.6.1.4.1.11129.2.1.17`）內的 `attestationChallenge` 必須等於本次 ceremony 的 WebAuthn challenge。
  6. `attestationSecurityLevel` 與 `keymasterSecurityLevel` 取較低者，須為 `TRUSTED_ENVIRONMENT`(TEE) 或 `STRONG_BOX`，否則 `422 HARDWARE_SECURITY_NOT_MET`。
  7. 支援的 COSE 演算法：ES256(-7) 與 RS256(-257)（對齊 `registration/options` 的 `pubKeyCredParams`）。實作建議優先 ES256。
- PoC 呼叫路徑（對齊 `api-contract.md` 前言）：真實產品中 Android APP **不直接**打 FIDO 伺服器；它透過同裝置 Credential Manager 與購物網站前端互動，結果由購物網站後端轉呼叫 FIDO 伺服器。PoC 階段為求快速驗證，可用一支**最小測試 harness（模擬購物網站前端 + 後端）**串接 Credential Manager 與 `fido-server` 端點；此 harness 僅供 PoC，不算產品程式碼。

---

## 2. 模擬器 vs 實機：本專案的驗證邊界

這是本清單的核心限制，務必先理解，否則會誤判「PoC 通過」。

- **模擬器（API 34）沒有真正的 StrongBox 安全晶片**，TEE 多為軟體模擬。模擬器產生的 Key Attestation 憑證鏈：
  - 鏈**不會**連到 Google 正式 Hardware Attestation root（模擬器用的是可公開取得的測試/軟體 attestation 金鑰）。
  - `securityLevel` 會回報 `SOFTWARE`（或非硬體等級）。
- 因此在 `fido.attestation.mode=real` 下，**模擬器註冊必然被伺服器以 `422 ATTESTATION_CHAIN_INVALID`（root 不受信）或 `422 HARDWARE_SECURITY_NOT_MET`（軟體等級）拒絕**。這不是 bug，正是硬體閘門該有的行為——所以模擬器可用來驗證「**負向路徑**」（軟體金鑰確實被擋下）。
- 要在模擬器上驗到「happy path 的流程接線是否走得通」（例如登入、多裝置、時效重試這些**與硬體聲明無關**的邏輯），做法是把伺服器切到 `fido.attestation.mode=stub`，讓 attestation 放行、先種出一筆 credential，再驗其餘流程。
- 「這台裝置的硬體聲明是不是真的」——**只有實機能證實**。模擬器無論如何都無法產生鏈到 Google root 的真硬體 attestation。

因此本清單三種標註定義如下：

| 標註 | 意義 |
|---|---|
| **模擬器可驗證** | 此項不依賴真硬體安全區，模擬器 + 適當伺服器模式即可完整驗收。 |
| **須實機驗證** | 此項本質上依賴真硬體或 OEM 客製，模擬器無法給出可信結論，須待實機。 |
| **兩者皆需** | 模擬器先驗「邏輯/接線正確」，實機再驗「硬體聲明屬實」；兩段判準分開列。 |

---

## 3. 10 項驗證清單總覽

| # | 驗證項目 | 關鍵? | 模擬器/實機標註 |
|---|---|---|---|
| 1 | `CredentialProviderService` 掛載到 Android 14 系統 Credential Manager 並被辨識為憑證提供者（非獨立 APP 跳轉） | ★ 關鍵 | 模擬器可驗證 |
| 2 | 硬體金鑰產生：`setIsStrongBoxBacked` 優先、無 StrongBox 時 fallback TEE、絕不靜默落純軟體 | ★ 關鍵 | 兩者皆需 |
| 3 | 自訂 provider 能輸出 `fmt="android-key"` 的 attestationObject，內含 Keystore attestation `x5c` 憑證鏈 | ★ 關鍵 | 兩者皆需 |
| 4 | Keystore 金鑰的 `attestationChallenge` 正確綁定到本次 WebAuthn ceremony 的 challenge | ★ 關鍵 | 兩者皆需 |
| 5 | 註冊 ceremony 端對端（options → provider create → server result）+ 硬體閘門正確判定 | ★ 關鍵 | 兩者皆需 |
| 6 | 登入 ceremony 端對端（assertion 簽章 + UV flag + sign counter 遞增） | 非關鍵（高） | 模擬器可驗證 |
| 7 | 多裝置註冊與個別撤銷互不干擾、`excludeCredentials` 防同機重複註冊 | 非關鍵 | 模擬器可驗證 |
| 8 | Challenge 60 秒時效在 Android 端的 UX 與逾時自動重新申請 | 非關鍵 | 模擬器可驗證 |
| 9 | Credential Manager 喚起速度與生物辨識/裝置解鎖（UV=required）確認流程順暢度 | 非關鍵 | 兩者皆需 |
| 10 | Android 14 OEM/廠牌客製化 Credential Manager 與 StrongBox 供應差異的相容性風險 | 非關鍵（但為實機上線 gating） | 須實機驗證 |

> **項目數說明**：任務原始面向清單有 9 個提示面向。本清單把其中「Key Attestation 能否正確產生並被伺服器解析」拆成**項目 3（能否輸出 android-key 憑證鏈）**與**項目 4（challenge 綁定是否正確）**兩項——理由見第 4 節：這兩者是**各自獨立的失敗模式**，一個是結構（產得出來嗎），一個是防重放綁定（綁對了嗎），伺服器對兩者回不同錯誤碼，混為一項會讓 qa 無法定位失敗點。拆分後恰為 10 項。

---

## 4. 關鍵項目排序邏輯

「關鍵項目」定義：**最高風險、最可能讓整個架構走不通的技術不確定性**——若此項證實做不到，方案 A（自訂 CredentialProviderService + 強制硬體 attestation）就得推翻或大改，而非小修。

依此準則，關鍵 5 項（★）與其風險理由：

1. **項目 1（provider 掛載）**：整個方案的地基。若自訂 `CredentialProviderService` 無法掛上 Android 14 Credential Manager、或系統不把它當可用憑證提供者（退化成獨立 APP 跳轉），CLAUDE.md「非獨立 APP 跳轉、非推播」的定位直接不成立。地基不成立則其餘全免談，故列風險第一。
2. **項目 2（硬體金鑰）**：CLAUDE.md「強制 TEE/StrongBox，不通過拒絕註冊」是本專案的安全賣點。若無法在程式碼層可靠地強制硬體、或無法可靠地偵測並拒絕落到軟體，安全承諾即落空。
3. **項目 3（android-key 輸出）**：**兩端對接的關鍵風險點**。伺服器**只**接受 `fmt="android-key"`（現行實作硬編碼），而多數 Android 平台 authenticator 產出的是 `none`/`packed` 而非 `android-key`。自訂 provider 必須自行用 Keystore attestation 憑證組出 android-key attestationObject。若證實做不到，**伺服器或 App 其中一端必須改**（見附錄 A-1），屬會動搖既有實作的高風險。
4. **項目 4（challenge 綁定）**：伺服器強制 `attestationChallenge == WebAuthn challenge`（否則 `CHALLENGE_MISMATCH`）。技術難點在於：自訂 provider 是否能在**產金鑰的當下**拿到本次 ceremony 的 challenge bytes 並塞進 `KeyGenParameterSpec.setAttestationChallenge()`。這是 provider 資料流的隱性耦合，做錯則註冊 100% 失敗且不易察覺，故列關鍵。
5. **項目 5（註冊端對端 + 硬體閘門）**：前 4 項的總整合。這是「整條註冊鏈路真的走得通、且硬體閘門判定正確」的收斂驗證，任何一個環節錯位都在此暴露。

**為何項目 6（登入端對端）不列關鍵**（此處與 CLAUDE.md 舊文字「#1–5 為關鍵」表述一致，但屬本文件首次給出具體對應，見附錄 A-4）：登入 assertion 是**標準 WebAuthn ES256 簽章驗證，與硬體 attestation 無關**，伺服器 assertion 路徑已實作並有單元測試覆蓋；一旦註冊能透過同一個自訂 provider 走通，登入走同一 provider 的 `getCredential` 路徑技術不確定性大幅下降。登入仍是**高重要度**（沒有它產品不能用），但**架構風險**低於註冊，故列第 6、非關鍵。此判斷屬分析師專業取捨，若人類希望維持「登入亦為關鍵」請於附錄 A-4 回覆確認。

項目 7–10 為功能完整度、體驗與相容性風險，做不到多半是「調整/補強」而非「架構走不通」，故非關鍵。其中**項目 10 雖非關鍵，但為實機上線前的 gating 條件**（不能只靠一台機型就宣稱相容）。

---

## 5. 逐項驗證細節與通過/失敗判準

> 判準一律寫成可觀察、可勾稽的條件（HTTP 狀態碼、DB 落列、log 欄位、實測數字），避免「應該沒問題」式描述。qa-engineer 可直接照此回報 PASS/FAIL。

### 項目 1（關鍵）CredentialProviderService 掛載與系統辨識

- **描述**：自訂 `CredentialProviderService` 能註冊進 Android 14 系統 Credential Manager，被系統辨識為可用的 passkey/公鑰憑證提供者，並在系統憑證流程中被喚起——而非跳出獨立 APP 自建 UI。
- **關鍵**：是。方案地基，見第 4 節第 1 點。
- **標註**：**模擬器可驗證**。系統 Credential Manager 於 API 34 模擬器即存在，provider 掛載/辨識/喚起不依賴真硬體。
- **通過判準**：
  1. App 於 `AndroidManifest` 宣告 `CredentialProviderService`（含 `android.service.credentials.CredentialProviderService` intent-filter 與對應 `<meta-data>` 能力宣告），安裝後於系統設定「密碼、密鑰與帳戶／Credential Manager 提供者」清單中出現本 App，且可被使用者設為啟用。
  2. 觸發一次 `CreatePublicKeyCredentialRequest`（由測試 harness 呼叫 `CredentialManager.createCredential`）時，系統把請求**路由到本 provider 的 `onBeginCreateCredentialRequest`**，log 可見本 service 被回呼。
  3. 使用者確認 UI 由**系統 Credential Manager bottom sheet** 呈現（provider 提供 entries），非本 App 全螢幕接管、非 `startActivity` 跳轉到獨立 APP 畫面。
- **失敗判準**：provider 未出現在系統提供者清單／請求未路由到本 service／或流程實際上是跳轉到獨立 APP 自建畫面（違反 CLAUDE.md「非獨立 APP 跳轉」定位）。任一成立即 FAIL。

### 項目 2（關鍵）硬體金鑰產生：StrongBox 優先、TEE fallback、絕不落軟體

- **描述**：以 `KeyGenParameterSpec` 產生 EC P-256 金鑰時，優先 `setIsStrongBoxBacked(true)`；捕捉 `StrongBoxUnavailableException` 後 fallback 到 TEE（不設 StrongBox）；並在產生後主動查核金鑰實際安全等級，若僅為軟體則**主動放棄註冊**而非送出。
- **關鍵**：是。對應 CLAUDE.md 強制硬體安全區，見第 4 節第 2 點。
- **標註**：**兩者皆需**。
  - 模擬器（先驗邏輯）：驗證「StrongBox 不可用 → 正確捕捉例外 → fallback 路徑被走到」的**程式分支**；以及「偵測到軟體等級 → App 端拒絕送出」的自我把關邏輯。模擬器上 `setIsStrongBoxBacked(true)` 應拋 `StrongBoxUnavailableException`，正好驗 fallback。
  - 實機（驗硬體聲明屬實）：驗證真的取得 StrongBox 或 TEE 金鑰。
- **通過判準（模擬器）**：
  1. 呼叫 `setIsStrongBoxBacked(true)` 時捕捉到 `StrongBoxUnavailableException`，程式進入 fallback 分支（log 明確標示 `STRONGBOX_UNAVAILABLE_FALLBACK_TEE`）。
  2. App 以 `KeyInfo.getSecurityLevel()`（API 31+）或 attestation 判讀實際等級；當等級為 `SECURITY_LEVEL_SOFTWARE`/非硬體時，App **不送出註冊**，並向使用者顯示「此裝置不支援硬體安全金鑰」類訊息。
- **通過判準（實機）**：`KeyInfo.getSecurityLevel()` 回報 `SECURITY_LEVEL_STRONGBOX` 或 `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`；支援 StrongBox 的機型（如 Pixel）應優先取得 StrongBox。
- **失敗判準**：StrongBox 不可用時未 fallback 而直接失敗或直接落軟體；或偵測到軟體等級仍照送（把關失效）。

### 項目 3（關鍵）android-key attestationObject 產生與 x5c 憑證鏈輸出

- **描述**：自訂 provider 能組出 WebAuthn `fmt="android-key"` 的 attestationObject，`attStmt` 內含 `alg`(-7)、`sig`（對 `authenticatorData || clientDataHash` 簽章）、`x5c`（Keystore `getCertificateChain()` 取得的 attestation 憑證鏈，leaf-first），且 `authenticatorData.credentialPublicKey` 與 `x5c[0]` 公鑰一致。
- **關鍵**：是。兩端對接最高風險點，見第 4 節第 3 點與附錄 A-1。
- **標註**：**兩者皆需**。
  - 模擬器（先驗結構）：CBOR 結構、欄位齊全、簽章自洽、公鑰一致——這些**與硬體無關**，可在模擬器完整驗，只是憑證鏈會鏈到測試 root（伺服器 real 模式會因此擋在 root 檢查，但結構正確性可先過）。
  - 實機（驗真憑證鏈）：`x5c` 鏈到 Google 正式 Hardware Attestation root。
- **通過判準（模擬器，結構自洽）**：
  1. 產出的 attestationObject CBOR 解得出 `fmt="android-key"`，`attStmt` 具備 `alg`/`sig`/`x5c` 三欄且型別正確（`x5c` 為 DER byte 陣列的陣列、非空）。
  2. 以本地測試驗證：`sig` 能被 `x5c[0]` 公鑰對 `authenticatorData || clientDataHash` 驗過（等同伺服器 `RealAttestationStatementVerifier` 的步驟 1）。
  3. `x5c[0]` 公鑰 == `authenticatorData` 內 `credentialPublicKey`（伺服器步驟 2）。
  4. 把此 attestationObject 送 `fido.attestation.mode=stub` 的伺服器 → `201`（證明結構被接受、僅差硬體 root）。
- **通過判準（實機，真憑證鏈）**：送 `fido.attestation.mode=real` 伺服器，`x5c` 通過 `RealAndroidKeyAttestationChainValidator` 的鏈驗到 Google root（不因 `ATTESTATION_CHAIN_INVALID`/`UNTRUSTED_ROOT` 被擋）。
- **失敗判準**：provider 只能產出 `none`/`packed` 等非 android-key 格式，或無法從 Keystore 取得可用的 attestation `x5c` 鏈 → 觸發附錄 A-1 決策（改伺服器接受格式或改 App）。伺服器回 `422 ATTESTATION_INVALID` 即 FAIL。

### 項目 4（關鍵）attestationChallenge 與 WebAuthn challenge 綁定

- **描述**：產金鑰時 `KeyGenParameterSpec.setAttestationChallenge(challenge)` 帶入的 challenge，必須等於本次註冊 ceremony（`registration/options` 回傳的 `publicKey.challenge`）的原始 bytes，使 leaf 憑證 extension 內 `attestationChallenge` 與伺服器記錄的 ceremony challenge 一致。
- **關鍵**：是。伺服器強制比對，資料流隱性耦合，見第 4 節第 4 點。
- **標註**：**兩者皆需**（模擬器可完整驗綁定邏輯；實機再確認真硬體 attestation 也如實承載）。
- **通過判準**：
  1. 於 provider 內把 WebAuthn 請求的 challenge（base64url 解碼後的 raw bytes）原封不動傳入 `setAttestationChallenge()`；log 記錄兩者 hex 相等。
  2. 端對端：以 `stub` 模式無法驗此項（stub 放行 challenge 檢查），故本項須以**能執行 `RealAndroidKeyAttestationChainValidator` 的模式**驗——模擬器可將伺服器設為「real 但暫時放寬 root 信任」的測試設定（見附錄 A-3），確認不因 `CHALLENGE_MISMATCH` 被擋；實機則直接走 real。
  3. 反向測試：故意帶入錯誤 challenge，伺服器須回可辨識的失敗（對應 `AttestationChainResult` 的 `CHALLENGE_MISMATCH`），證明綁定確實被驗。
- **失敗判準**：正確帶入 challenge 卻仍 `CHALLENGE_MISMATCH`（表示編碼/位元組處理有誤），或未實作綁定（provider 用固定/隨機 challenge）。

### 項目 5（關鍵）註冊 ceremony 端對端 + 硬體閘門

- **描述**：完整走 `POST /registration/options` →（Credential Manager create，provider 產金鑰+attestation）→ `POST /registration/result`，並驗證硬體閘門在兩種等級下判定正確。
- **關鍵**：是。前 4 項的收斂整合，見第 4 節第 5 點。
- **標註**：**兩者皆需**。
- **通過判準（模擬器，負向路徑=硬體閘門正確擋下軟體）**：
  1. 於 `fido.attestation.mode=real` 送出模擬器產生的 attestation，伺服器回 `422 HARDWARE_SECURITY_NOT_MET` 或 `422 ATTESTATION_CHAIN_INVALID`，且 `error.details` 內回報偵測到的等級為 `SOFTWARE`／root 不受信。此為**預期正確行為**（軟體金鑰被拒），記為 PASS（負向）。
  2. 於 `fido.attestation.mode=stub` 送出，伺服器回 `201`，並於 DB 落 `fido_credentials`（`status=ACTIVE`, `sign_count=0`）與 `bound_devices` 各一列——證明流程接線與欄位對應正確（正向流程，硬體聲明除外）。
- **通過判準（實機，正向路徑=真硬體被接受）**：
  1. `fido.attestation.mode=real`，回 `201`，`response.device.securityLevel ∈ {TEE, STRONG_BOX}`，且與該機型實際能力一致（Pixel 應為 STRONG_BOX）。
  2. DB `bound_devices.security_level` 落 `TEE`/`STRONG_BOX`（對齊 `db-schema.md` CK 約束，`SOFTWARE` 不得落庫）。
  3. `excludeCredentials` 生效：同機再次註冊同使用者應被擋（見項目 7）。
- **失敗判準**：real 模式下模擬器竟通過（表示硬體閘門失效）；或 stub 模式流程接線錯誤（DB 未如實落列、欄位對不上 `api-contract.md` 2.2 Response）。

### 項目 6 登入 ceremony 端對端（assertion + sign counter）

- **描述**：完整走 `POST /authentication/options` →（Credential Manager get，provider 用同一把金鑰簽 assertion）→ `POST /authentication/result`，並驗證 UV flag 與 sign counter 行為。
- **關鍵**：否（高重要度，架構風險低）。理由見第 4 節「為何項目 6 不列關鍵」。
- **標註**：**模擬器可驗證**。assertion 是標準 WebAuthn 簽章驗證，**與硬體 attestation 無關**；只要先以項目 5 的 stub 模式種出一筆 credential，登入即可在模擬器完整驗收。
- **通過判準**：
  1. 用項目 5 stub 模式種出的 credential 執行登入，伺服器回 `200`、`verified=true`，並簽發 `session.token`（JWT，`expiresIn=120`，可用 `/.well-known/jwks.json` 驗簽、`alg=ES256`）。
  2. `authenticatorData` 的 UV flag 為真（對應 `userVerification:"required"`；需完成模擬器的螢幕鎖/生物辨識模擬）。
  3. sign counter 行為：連續兩次登入，`fido_credentials.sign_count` 遞增（或 authenticator 恆為 0 時伺服器放行不更新，二者其一，對齊 `api-contract.md` 3.2 步驟 4）。
  4. 反向：人為送出 counter 倒退的 assertion，伺服器回 `422 SIGN_COUNTER_REGRESSION`，且該 credential 與 `bound_devices` 被標 `REVOKED`、`revoked_reason=COUNTER_REGRESSION`（對齊自動撤銷決策）。
- **失敗判準**：`200` 但 UV flag 為假；或簽章無法被伺服器以既存公鑰驗過（`422 ASSERTION_INVALID`）；或 counter 倒退未觸發自動撤銷。

### 項目 7 多裝置註冊與個別撤銷互不干擾

- **描述**：同一 `externalUserId` 下註冊多台裝置（多把 credential），個別撤銷其一不影響其餘；`excludeCredentials` 防同機重複註冊。
- **關鍵**：否。
- **標註**：**模擬器可驗證**（以 stub 模式 + 多個模擬器 AVD 或重置 Keystore 模擬多裝置；純為邏輯驗證，不涉真硬體）。
- **通過判準**：
  1. 同使用者註冊兩把 credential（兩台模擬裝置），`fido-status`(5.1) 回 `activeDeviceCount=2`、`canUseFido=true`；`devices`(4.1) 列出兩列。
  2. `DELETE .../devices/{deviceIdA}` 後：裝置 A 於 4.1（`status=ACTIVE` 過濾）消失、`ALL` 過濾仍在且 `status=REVOKED`（軟刪除，對齊 D10/DB10）；裝置 B 仍可正常登入。
  3. 撤銷至 0 台後 `canUseFido=false`，且**允許**（不擋最後一台，對齊帳密永久保留決策）。
  4. `excludeCredentials`：同一台已註冊的裝置對同使用者再次註冊，被擋（`409 CREDENTIAL_ALREADY_EXISTS` 或前端 Credential Manager 依 `excludeCredentials` 拒絕）。
- **失敗判準**：撤銷 A 導致 B 失效；或撤銷後仍能登入；或同機重複註冊未被擋。

### 項目 8 Challenge 60 秒時效的 Android 端體驗與重試

- **描述**：challenge 逾 60 秒後，Android 端能偵測到 `400 CHALLENGE_EXPIRED` 並依 CLAUDE.md「逾時前端自動重新申請」重跑 options，UX 不卡死。
- **關鍵**：否。
- **標註**：**模擬器可驗證**（時效為伺服器端 `auth_challenges.expires_at` 邏輯，與硬體無關）。
- **通過判準**：
  1. 取得 options 後故意延遲 >60 秒再送 result，伺服器回 `400 CHALLENGE_EXPIRED`。
  2. Android 端捕捉此錯誤後，**自動**重新呼叫對應 options 取得新 challenge 並可再次完成 ceremony，全程不需使用者手動重來、不崩潰。
  3. 前端 `timeout:60000` 提示與伺服器權威時效一致（前端提前提示、伺服器最終判定）。
- **失敗判準**：逾時後 App 卡死/崩潰／或把 `CHALLENGE_EXPIRED` 當致命錯誤不重試。

### 項目 9 Credential Manager 喚起速度與生物辨識/裝置解鎖 UX

- **描述**：測量 Credential Manager bottom sheet 喚起延遲，並確認 UV=required 下生物辨識/裝置解鎖確認流程順暢、可取消、可重試。
- **關鍵**：否（UX 風險，非架構走不通）。分析師評估：此項不足以否決方案 A，故不列關鍵；但列入 PoC 以早期發現體驗地雷。
- **標註**：**兩者皆需**。模擬器可測「喚起→回呼」的軟體延遲與流程可用性；但生物辨識為模擬，**真實指紋/臉部辨識體驗與硬體延遲須實機**。
- **通過判準**：
  1. （模擬器）從觸發 `createCredential`/`getCredential` 到系統 UI 出現，觀測延遲並記錄基準值；流程可完成、可取消（取消回傳 `GetCredentialException`/使用者取消而非崩潰）。
  2. （實機）真實生物辨識喚起到可操作 < 約 2 秒（PoC 觀察基準，非硬性 SLA，見附錄 B）；辨識失敗可重試、取消有明確回饋。
- **失敗判準**：喚起明顯卡頓到不可用；或 UV 流程無法取消/重試導致使用者卡住。

### 項目 10 Android 14 OEM/廠牌客製化相容性風險

- **描述**：不同 OEM（Samsung/Xiaomi/Pixel 等）對 Credential Manager 的客製化實作、以及 StrongBox 供應差異，可能造成 provider 行為或 attestation 結果不一致。
- **關鍵**：否（但為**實機上線前 gating**：不得只憑單一機型宣稱相容）。
- **標註**：**須實機驗證**。此項本質上模擬器無法涵蓋——模擬器只有 AOSP 參考實作，測不到 OEM 客製差異。
- **模擬器階段如何處理**：PoC 模擬器階段**不對本項下 PASS/FAIL**，僅產出「已知 OEM 相容性風險清單」（例如：部分 OEM 是否允許第三方 provider、StrongBox 是否普遍供應、是否有廠商把 Credential Manager 導回自家實作），標記為 open risk 待實機批次驗證。
- **通過判準（實機，取得裝置後）**：
  1. 至少涵蓋 **2 個不同 OEM** 的 Android 14 機型（建議含 1 台有 StrongBox、1 台僅 TEE），項目 1/2/5/6 在每台上重跑皆 PASS。
  2. 若某 OEM 不支援第三方 CredentialProviderService 或強制導回自家 UI，須明確記錄為「該機型不支援」並評估市佔影響，回報產品決策，而非默默視為通過。
- **失敗判準（gating）**：僅在單一機型驗過即宣稱相容；或發現主流 OEM 系統性阻擋第三方 provider 卻未上報。

---

## 附錄 A：推導過程中發現需人工確認的點

> 以下為推導本清單時，發現 CLAUDE.md / 既有合約尚未涵蓋、且會影響 PoC 判定或後續架構的點。**列此供人類確認，非本文件片面決定。**

- **A-1（高，牽動既有實作）attestation 格式硬耦合**：`fido-server` 目前**只**接受 `fmt="android-key"`（`RealAttestationStatementVerifier` 硬編碼）。自訂 CredentialProviderService 能否穩定產出 android-key 格式的 attestationObject，是項目 3 的核心風險。若 PoC 證實不可行（例如只能拿到 `packed`/`none`，或 Keystore attestation 鏈難以塞進 WebAuthn attStmt），則需人類拍板：**改伺服器接受的格式**，或**改 App 產出方式**。建議 PoC 第一週優先打通項目 3 以儘早暴露此風險。
- **A-2 測試 harness 定位**：PoC 需一支模擬「購物網站前端+後端」的最小 harness 來串接 Credential Manager 與 `fido-server`（因產品中 App 不直接打 FIDO API）。此 harness 屬 PoC 拋棄式程式，請確認不需納入正式碼庫維護。
- **A-3 模擬器階段的伺服器測試模式**：項目 4 需要能執行憑證鏈/challenge 驗證但暫時放寬「必須鏈到 Google root」的中間模式，現行只有 `real`（全嚴格）與 `stub`（全放行）二態。建議由 dev-engineer 於 PoC 期間加一個**測試專用**的信任根注入（把模擬器測試 root 暫加入 `TrustedRootCertificateStore`），僅限 PoC profile、不得進 production 設定。請確認此作法可接受。
- **A-4 「登入是否為關鍵項目」**：CLAUDE.md 舊表述「#1–5 為關鍵」未有具體對應。本文件依風險把關鍵定為項目 1–5（含註冊端對端），將登入端對端列為第 6（非關鍵、高重要度），理由見第 4 節。若人類希望登入仍列關鍵，請回覆，我調整並回填。
- **A-5 CLAUDE.md 回填**：本清單定案並經人工複核後，建議把「目前階段」段落中抽象的「10 項驗證項目、#1–5 為關鍵」改為指向本文件（`docs/android-poc-checklist.md`），避免兩處表述漂移。此更新我可代為執行，但**等人工確認後**再改 CLAUDE.md，不先斬後奏。

## 附錄 B：PoC 通過門檻建議（待人工確認）

> CLAUDE.md 說「PoC 通過後才進入正式開發時程」，但**未明訂「通過」的具體門檻**。以下為建議標準，供人類確認；未經確認前不視為既定決策。

建議「PoC 通過」= 同時滿足：

1. **關鍵 5 項全數 PASS**，且項目 2/3/4/5 的**實機正向判準**至少在 1 台真機（建議 Pixel，具 StrongBox）通過——關鍵項目不得只靠模擬器負向/stub 結果宣稱通過。
2. 項目 6、7、8 至少在**模擬器**PASS（這三項不依賴真硬體）。
3. 項目 9 取得模擬器基準數據 + 至少 1 台實機的生物辨識 UX 無阻斷性問題。
4. 項目 10 於 PoC 模擬器階段完成「OEM 風險清單」文件化；實機階段（至少 2 OEM）留待 PoC 通過後、正式開發早期補驗，並在此前不得對外承諾相容機型範圍。
5. 附錄 A-1（attestation 格式）已有明確結論（可行，或已定調改哪一端）。

若因無實機而**無法完成第 1、3 條的實機部分**，建議 PoC 結論記為「**條件式通過（pending 實機）**」：模擬器可驗部分全綠、關鍵風險（provider 掛載、格式產出、流程接線）已排除，但硬體聲明屬實與 OEM 相容性列為「取得實機後的收尾驗證」，並據此決定是否可先啟動正式開發的非硬體相依部分。此條件式結論是否可作為放行依據，請人類裁示。
