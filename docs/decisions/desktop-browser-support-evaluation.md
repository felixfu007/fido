# 桌機瀏覽器支援 FIDO 登入 — 方案評估

> **狀態：評估文件，尚未拍板，待專案擁有者決策。**
> 本文只做方案比較與影響分析，不下最終決策、未更動 `CLAUDE.md`、未寫任何程式碼。
> 文件最後的「專業建議」段落是**建議傾向**而非決策，供決策者參考。
>
> 撰寫：systems-analyst｜日期：2026-07-24｜對照原始碼版本：`main` @ 5388ca4

---

## 0. 問題與現況根因（已查證原始碼）

專案擁有者的提問：「如果我希望電腦瀏覽器也能使用 FIDO 登入，有哪些方法？需要做哪些調整？」

目前桌機完全不支援的**兩個獨立根因**（兩者都要處理才可能開通桌機）：

1. **伺服器只認單一 attestation 格式。**
   `fido-server/.../webauthn/RealAttestationStatementVerifier.java:39` 把支援格式寫死為
   `SUPPORTED_FORMAT = "android-key"`，第 55–59 行對任何非 `android-key` 格式直接 `return false`。
   桌機平台驗證器產生的 `tpm`（Windows Hello）、`apple`（macOS 裝置綁定）、`packed` / `fido-u2f`
   （USB 安全金鑰）、`none`（Safari 同步 passkey）全數被拒。

2. **伺服器強制平台驗證器 + 硬體安全等級。**
   - `RegistrationService.createOptions()`（`RegistrationService.java:126`）把
     `authenticatorSelection.authenticatorAttachment` 設為 `"platform"`，這會**在瀏覽器端就排除**
     跨平台漫遊驗證器（USB 安全金鑰）；即使伺服器願意收 `packed`，前端也不會提供該選項。
   - `verifyResult()`（`RegistrationService.java:198–203`）要求 `AttestationChainResult.securityLevel()`
     非 null，且 `SecurityLevel` enum（`domain/enums/SecurityLevel.java`）只有 `TEE` / `STRONG_BOX`
     兩個值，`bound_devices.security_level` 的 DB CHECK 約束也只允許這兩值。這是 Android Key
     Attestation 專屬的判讀（`RealAndroidKeyAttestationChainValidator` 解析 Android extension
     OID `1.3.6.1.4.1.11129.2.1.17`）——桌機的 TPM / Secure Enclave / 安全金鑰**沒有對應的等級可填**。

換句話說：即使只把第 1 點的 `android-key` 白名單放寬，第 2 點的 `authenticatorAttachment=platform`
與 `SecurityLevel` 二值模型仍會擋下桌機。**兩處都要動**，這也是為什麼下方所有「伺服器端方案」的複雜度
都不低於「中」。

現有可插拔基礎（有利因素）：`AttestationStatementVerifier` 與 `AndroidKeyAttestationChainValidator`
**已經是介面**，且用 `@ConditionalOnProperty` 做 real/stub 切換。要擴充成多格式，是把「單一實作」
改為「依 `fmt` 分派到多個實作」，介面契約本身不需推倒重來。`fido_credentials.attestation_format`
欄位也已存 `fmt`，多格式落庫不需要改 schema 的這一欄。

---

## 方案 A：伺服器端支援多種 attestation 格式，桌機各自獨立註冊

桌機使用者用自己平台的驗證器**在該台裝置上獨立註冊一把新金鑰**（與手機的 `android-key` 私鑰是不同的
金鑰，非「同一把兩邊用」）。涵蓋格式：`tpm`（Windows Hello）、`apple`（macOS 裝置綁定）、
`packed` / `fido-u2f`（YubiKey 等安全金鑰），以及**無法迴避的 `none`**（見下）。

### 已查證的關鍵事實：Safari / macOS 的常見情況是「沒有 attestation」

Apple 官方立場：**passkey 在 Safari 建立時不提供 attestation statement**，因為 passkey 會跨裝置
同步、單次 attestation 無法對所有同步到的裝置做安全保證。實務上 Safari 建立的 passkey 回傳 `fmt="none"`
且 AAGUID 全為零（來源見文末）。也就是說：
- 想支援「macOS/Safari 上最主流的同步 passkey」，伺服器就**必須接受 `none`**（零 attestation）；
- 若堅持要 `apple` 格式的硬體 attestation，只能涵蓋「裝置綁定、且明確要求 attestation」的少數情況，
  無法涵蓋 iCloud 同步 passkey 的大宗使用者。

這一點直接衝擊本平台的核心安全承諾（見下方 §安全性意涵），是方案 A 最需要決策者拍板的地方，**不是純工程問題**。

### 1. 需要調整的檔案 / 元件（server 端）

- `webauthn/AttestationStatementVerifier.java`：介面保留，改為「依 `fmt` 分派」的註冊表模式
  （例如新增 `AttestationVerifierRegistry`，把 `fmt` → 對應 verifier bean 對起來）。
- `webauthn/RealAttestationStatementVerifier.java`：移除 `SUPPORTED_FORMAT` 硬編碼；拆成每格式一個實作：
  `TpmAttestationStatementVerifier`、`AppleAttestationStatementVerifier`、`PackedAttestationStatementVerifier`、
  `FidoU2fAttestationStatementVerifier`、`NoneAttestationStatementVerifier`（現有 android-key 邏輯保留）。
  各格式 `attStmt` 結構與簽章對象都不同（尤其 TPM 的 `certInfo`/`pubArea` 解析相當繁瑣）。
- `webauthn/AndroidKeyAttestationChainValidator.java` / `RealAndroidKeyAttestationChainValidator.java`：
  目前是 Android 專屬。需抽象出一個「憑證鏈 / 信任源驗證」的一般介面（例如 `AttestationTrustValidator`），
  Android extension 判讀留在 android-key 實作，另建：
  - TPM：驗 AIK 憑證鏈到 **TPM 廠商 EK root**（Infineon / STMicro / Intel / AMD… 多家、且會輪替）。
  - Apple：驗 nonce extension（OID `1.2.840.113635.100.8.2`）並鏈接到 **Apple WebAuthn Root CA**。
  - packed / fido-u2f：以 AAGUID 查 **FIDO Alliance Metadata Service（MDS）** 取得該型號的信任錨與 metadata。
- `webauthn/TrustedRootCertificateStore.java`：新增 Apple root、TPM 廠商 root；另需新元件
  `FidoMetadataService`（定期抓取並驗證 MDS BLOB 簽章）——**注意這需要對外連網，與「全地端部署」抵觸**，見 §風險。
- `webauthn/AttestationChainResult.java` + `domain/enums/SecurityLevel.java`：`SecurityLevel` 要擴充
  以表達 TPM / Apple SEP / 安全金鑰硬體等級（或新增一層「硬體來源分級」對應表），今日只有 `TEE`/`STRONG_BOX`。
- `service/RegistrationService.java`：`createOptions()` 的 `authenticatorSelection` 必須放寬
  （支援安全金鑰要允許 `cross-platform`）；`verifyResult()` 的 securityLevel 政策要改為**逐格式**判定
  可否接受；`pubKeyCredParams` 可能要補 EdDSA（-8）。
- DB / infra：`bound_devices.security_level` 的 CHECK 約束（`infra/sql/` 與 JPA 實體）要放寬到新等級集合。
- `exception/ErrorCode.java`：視政策可能新增錯誤碼（例如「該格式不被此租戶接受」）。

### 2. 工程複雜度：**大**

理由：每種格式幾乎是一個獨立小專案。TPM attestation 驗證公認複雜；FIDO MDS 是**長期維運負擔**
（週期性抓取 + 簽章驗證 + 撤銷處理），且與地端部署衝突；信任源（Apple root、TPM 廠商 root、MDS）
各有更新節奏。加上第 0 節的 `authenticatorAttachment` 與 `SecurityLevel` 模型改動，牽動註冊選項、
落庫、稽核、API 合約多處。

### 3. 安全性意涵（**重大，會改變核心承諾**）

- `CLAUDE.md`「金鑰保護：強制要求 TEE/StrongBox…不通過則拒絕註冊」是目前對客戶的核心賣點。
- 桌機平台的硬體保證**無法與 Android StrongBox 對等**：TPM 可提供真實硬體 attestation（等級語意不同）；
  安全金鑰是專用硬體（但信任建立在 MDS metadata）；**Safari 同步 passkey 是 `none`，等於零 attestation、
  完全無硬體證據**。
- 因此方案 A 會迫使一個政策二選一：(a) 維持「桌機各平台分級、誠實對外揭露等級不一」——把單一
  「強制 StrongBox」承諾改寫成「分級信任模型」；或 (b) 為了覆蓋率接受 `none`/self-attestation——
  等於對桌機放棄硬體保證。無論選哪個，對客戶的溝通話術都要改。

### 4. 使用者體驗意涵（**佳**）

- 「每台裝置各自註冊一把金鑰」**本來就是現有多裝置模型**（`CLAUDE.md`「允許使用者註冊多台裝置」），
  不是新的體驗退化。
- 無 QR code、無掃碼步驟，桌機使用者直接用 Windows Hello / Touch ID / 安全金鑰在該機註冊、登入。
- 每台新裝置第一次要註冊一次，符合使用者對 passkey 的普遍預期。

### 5. 對現有客戶 / 文件的影響（採用後需更新，非現在改）

`docs/vendor/technical-limitations.md`（第 1、2 項改寫）、`docs/vendor/api-integration-guide.md`
（attestation 格式、authenticatorSelection、securityLevel 值）、`docs/vendor/usage-scenarios-guide.md`、
`docs/api-contract.md`（註冊選項、錯誤碼、securityLevel 列舉）、`docs/db-schema.md`（security_level 值域）、
以及 `CLAUDE.md` 的「金鑰保護 / 支援裝置 / 架構情境」決策（須擁有者簽核）。

### 6. 風險與未知數

- **FIDO MDS 維運負擔 + 地端衝突**：MDS 需對外抓取更新，`CLAUDE.md`「部署：全地端部署（非雲端）」
  意味要嘛開放外連、要嘛人工定期匯入 BLOB，兩者都是新的維運流程。
- **TPM 廠商 root 管理**：多廠商、會輪替；EK 憑證另有隱私考量。
- **Safari `none` 政策**：接受就破壞硬體承諾，拒絕就排除多數 Mac 使用者——產品定位問題。
- **Conformance**：現有限制第 5 項（未做 FIDO Conformance）在多格式下驗證面更大。

---

## 方案 B：重啟情境 B — 用手機當桌機的漫遊驗證器（WebAuthn hybrid / caBLE）

專案擁有者提出的前提是：桌機跳 QR code、手機掃碼、**仍由本 App（`FidoCredentialProviderService`）
產生同一把 `android-key` 簽章**，因此不必動伺服器 attestation。**經查證，這個前提在目前架構下不成立**，
是本方案最關鍵的發現。

### 先釐清：caBLE/hybrid 與「已放棄的情境 B（跨裝置推播）」是否同一件事？

- **概念不同，擁有者的直覺是對的。** `CLAUDE.md` 記載放棄的是「情境 B（**跨裝置推播**）」——那是自訂
  推播 / 帶外核准的流程；WebAuthn **hybrid/caBLE 是 W3C/FIDO 官方標準機制**（QR + BLE 近距證明 +
  雲端加密通道），兩者是不同技術路線。
- **但「先前放棄的理由是否仍適用」這題，`CLAUDE.md` 只留下「已放棄」的結論，未留詳細理由**（我查了
  CLAUDE.md、docs/、可讀到的紀錄，沒有更細的脈絡；git log 工具在本次環境不可用，無法追溯當時 commit
  訊息）。因此無法宣稱「舊理由原封不動適用」。不過下方查證出的新阻礙，足以獨立說明為何 hybrid 在
  **目前這個以自訂 provider + 硬體 attestation 為核心的架構**下仍不適合。

### 已查證的關鍵事實：自訂 provider 的憑證不會被 hybrid 使用

- hybrid/caBLE 在**手機端是由 Google Play Services 扮演 CTAP2 驗證器**來處理；桌機掃到的、手機透過
  hybrid 提供的 passkey，是**平台驗證器（Google Password Manager）**的 passkey。
- `androidx.credentials.provider` 沒有讓第三方 `CredentialProviderService` 參與「跨裝置 hybrid」的 API：
  我方 `FidoCredentialProviderService.onBeginGetCredentialRequest` 只在**同一台裝置**的 Credential
  Manager 流程被叫用，不會在「手機當別台裝置的漫遊驗證器」時被叫用。（來源：Android 官方 provider
  文件與社群查證，見文末；此點建議取得實機後再實測確認，但方向明確。）
- 推論：就算走通 hybrid，實際簽章的會是 **Google Password Manager 的同步 passkey**，其格式是
  **`fmt="none"`**（見方案 A 查證）——伺服器目前直接拒絕，且它與我方 `android-key` 是不同金鑰、
  不同 provider。**擁有者前提中「仍由本 App 產生 android-key」不會發生。**

### 這代表什麼

要讓 hybrid 真的能登入，等於**繞回方案 A 的一個子集（伺服器必須接受平台同步 passkey 的 `none`）**，
而且：(a) 完全用不到本專案投入的自訂 provider 與硬體 attestation；(b) 依賴 Google/Apple 的雲端隧道
與 BLE 基礎設施；(c) 同步 passkey 無 attestation，等於對這條路放棄硬體保證。因此 B 不是「便宜的捷徑」，
反而是「成本≈A 的 none 子集，但額外犧牲整個自訂 provider 架構價值」。

### 逐項回答

1. **調整範圍**：Android 端**沒有可行的改動**能讓自訂 provider 參與 hybrid（非 App 宣告可解，屬平台層）；
   若改走平台 passkey，伺服器端要做的其實就是方案 A 的 `none` 支援那一塊。
2. **複雜度**：Android 端無事可做（也無收穫）；伺服器端≈「A 的 none 子集」＝**中**，但**架構代價大**。
3. **安全性**：走 hybrid 實際用的是無 attestation 的同步 passkey，**直接抵觸強制硬體安全區承諾**，且無法
   驗證私鑰真的在安全硬體。
4. **UX**：每次登入要掏手機掃 QR + BLE 配對，比方案 A 的「桌機本機生物辨識」多好幾步，且依賴藍牙可用性。
5. **文件影響**：同方案 A 的 `none` 部分，另需在使用情境手冊說明跨裝置流程與其安全等級差異。
6. **風險**：依賴 Google/Apple 雲端中介（地端部署下的外部相依）；第三方 provider 是否被 hybrid 曝露
   需實機確認（現況指向「否」）；BLE 相容性；本專案自訂 provider 投資無法沿用。

---

## 方案 C：混合式 — 先只做 Windows Hello（`tpm`），之後再視需求擴充

方案 A 的最小可行子集：先只開通 `tpm`（Windows Hello 平台驗證器）。

- **檔案 / 元件**：與方案 A 的架構重構相同（verifier 註冊表、trust validator 抽象、SecurityLevel 擴充、
  `authenticatorSelection` 調整），但**只實作 `tpm` 一個 verifier + TPM 廠商 root 信任源**，暫不碰
  Apple/`none`、安全金鑰/MDS。
- **複雜度：中～大**。少了 MDS 與多格式，但 TPM attestation 本身解析（`certInfo`/`pubArea`/AIK 鏈）
  仍是硬骨頭；第 0 節的共用重構（分派架構、SecurityLevel、DB CHECK、authenticatorAttachment）一樣要做，
  這部分是「一次投入、A 全量時可重用」的地基。
- **安全性：相對最能保住承諾**。Windows Hello TPM 提供**真實硬體 attestation**，可對應新增一個硬體等級
  （例如 `WINDOWS_TPM` 或一般化的 `HARDWARE_TPM`），精神上與「金鑰在硬體安全區」一致，**不需要接受 `none`**。
  （注意：Windows 在無法 attest 時也可能回 `none`，此時比照「不達標則拒絕」處理即可，不破壞承諾。）
- **UX**：桌機市占最大宗（Windows）先受惠，本機生物辨識、無掃碼、每機註冊一次。
- **文件影響**：範圍同 A，但先只需標註「桌機支援 Windows Hello，其他桌機平台後續評估」，對外訊息單純。
- **風險**：TPM 廠商 root 管理；未涵蓋 Mac / 安全金鑰的使用者仍走帳密；日後擴充到 A 全量時 `none` 政策
  問題依舊會浮現（只是延後面對）。

---

## 總結比較表

| 方案 | 工程複雜度 | 安全意涵（對「強制硬體安全區」承諾） | UX 意涵 | 建議適用情境 |
|---|---|---|---|---|
| **A 全量多格式** | **大** | 迫使二選一：改寫成「分級信任模型」或為覆蓋率接受 `none`（放棄桌機硬體保證） | 佳：本機生物辨識/安全金鑰，每機註冊一次，無掃碼 | 要一次覆蓋 Windows+Mac+安全金鑰、且願意重新定義並溝通硬體承諾 |
| **B hybrid/caBLE** | Android 端無可行改動；伺服器端≈A 的 `none` 子集（中），**架構代價大** | 實際用無 attestation 的同步 passkey，**直接抵觸承諾**，且用不到自訂 provider | 差：每次登入掏手機掃 QR+BLE，步驟最多、依賴藍牙 | 不建議：前提不成立，且犧牲整個自訂 provider 架構價值 |
| **C 先做 Windows Hello（tpm）** | 中～大 | **最能保住承諾**：TPM 真實硬體 attestation，可新增硬體等級，不需接受 `none` | 佳：Windows 使用者本機生物辨識，每機註冊一次 | **想要漸進、優先覆蓋最大桌機族群、又不想動搖硬體承諾** |

---

## 專業建議（建議，非決策）

**傾向建議：若確定要投入桌機支援，走方案 C（先做 Windows Hello / `tpm`），把方案 A 全量與方案 B 分別視為
「後續擴充」與「不採用」。**

讓我傾向 C 的考量：

1. **B 的前提查證後不成立。** hybrid 手機端由 Google Play Services 主導，我方自訂
   `FidoCredentialProviderService` 的 `android-key` 憑證不會被 hybrid 使用；真正會簽的是平台同步 passkey
   （`fmt="none"`），繞回「伺服器接受 none」的老問題，還額外犧牲本專案在自訂 provider + 硬體 attestation
   上的全部投資。除非未來實機實測推翻此結論，否則 B 性價比最差。
2. **C 是唯一能在「開通桌機」與「守住核心安全承諾」之間兩全的路。** Windows Hello 的 TPM 提供真實硬體
   attestation，可自然對應到現有「金鑰在硬體安全區」的定位，不必被迫接受無 attestation 的 `none`；同時
   Windows 是桌機最大宗，覆蓋率報酬率最高。
3. **C 的地基與 A 共用、可重用。** verifier 分派架構、`SecurityLevel` 擴充、`authenticatorAttachment`
   放寬、DB CHECK 調整這些「一次性重構」在 C 就要做，日後要擴到 Mac / 安全金鑰時直接接續，不是白工。
4. **把最棘手的政策題延後、但不迴避。** 真正需要擁有者拍板的不是工程，而是「Safari 同步 passkey 的
   `fmt="none"` 要不要收」——這是「要覆蓋率還是要硬體保證」的產品定位抉擇。C 讓你先拿到 Windows 的成果，
   把這題留到擴充 Mac 時再正式決策，而不是在第一步就被迫回答。

**但這只是傾向。** 若擁有者的實際使用者以 Mac/Safari 為主，或商業上「覆蓋率 > 硬體保證」，那方案 A 全量
（並明確改寫硬體承諾為分級信任模型）才是對的選擇——這需要擁有者對「是否接受 `none` / 重新定義安全承諾」
拍板，systems-analyst 不越權替您決定。無論選 A 或 C，都建議在動工前先把「`none` 政策」與
「`CLAUDE.md` 金鑰保護決策是否改寫」這兩題結論寫下來，再交 dev-engineer 承接。

---

## 附錄：查證來源

- Safari passkey 回傳 `fmt="none"`、AAGUID 全零、Apple 不對 passkey 提供 attestation：
  <https://www.slashid.dev/blog/passkeys-deepdive/>、Apple Developer Forums
  <https://developer.apple.com/forums/thread/713195>、<https://developer.apple.com/forums/thread/726208>
- WebAuthn hybrid/caBLE 機制（QR + BLE 近距 + 雲端隧道）：
  <https://www.corbado.com/blog/webauthn-transports-internal-hybrid>、
  <https://www.corbado.com/blog/webauthn-passkey-qr-code>
- Android Credential Manager 與第三方 credential provider（provider 僅由 GMS 綁定、同裝置流程）：
  <https://developer.android.com/identity/sign-in/credential-provider>、
  passkeys.dev Android 參考 <https://passkeys.dev/docs/reference/android/>
- 原始碼查證：`fido-server/src/main/java/com/fido/server/webauthn/RealAttestationStatementVerifier.java:39`、
  `RealAndroidKeyAttestationChainValidator.java`、`service/RegistrationService.java:126,198`、
  `domain/enums/SecurityLevel.java`、`android-credential-provider/.../FidoCredentialProviderService.kt`
