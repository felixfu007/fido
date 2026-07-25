# FIDO/FIDO2 認證系統 — 專案共識

四個系統：FIDO 驗證伺服器、FIDO 驗證 APP（Android）、採用 FIDO 登入的購物網站、資料主機（SQL Server）。
後端：Spring Boot + Maven。架構情境：**A（標準 WebAuthn，同裝置）**為主，另有已拍板的**情境三（跨裝置 QR transaction confirmation）**；情境 B（跨裝置推播）已放棄。情境 A 的使用者存取涵蓋「手機瀏覽器存取購物網站」與「購物網站原生 Android App 內直呼 Credential Manager」兩種前端情境（後者對每個租戶 opt-in，見「WebAuthn origin 綁定 / 存取情境」決策與 `docs/origin-binding.md`）。

**三個情境的區隔（沿用 `docs/decisions/qr-cross-device-login-design.md` 8.1）：**

- **情境 A（同裝置）**：發起與生物辨識簽章在同一台裝置（手機）；origin 由 OS/瀏覽器或原生 App 呼叫方擔保，走 Credential Manager。v1 主線。
- **情境三（跨裝置 QR transaction confirmation）**：桌機瀏覽器發起、顯示 QR，手機 App 掃碼後作**漫遊確認器**，用**手機上既有、已通過 TEE/StrongBox 註冊的憑證**做一次**真正的 WebAuthn assertion**（非裸旗標）送回伺服器；桌機輪詢取得結果。是 **pull-based QR + 真 WebAuthn assertion**，**不使用** WebAuthn hybrid/caBLE。安全模型與範圍限制見下方決策表「跨裝置 QR 登入」數列與 `docs/decisions/qr-cross-device-login-design.md`（設計）、`docs/api-contract.md` §3.4（端點）。
- **情境 B（跨裝置推播）**：自訂推播/帶外 boolean 核准，**已放棄**。與情境三的關鍵差異：情境三手機端做的是**密碼學真簽章**、伺服器驗簽不信任 boolean；情境 B 是裸核准，故安全性本質不同。

## 已確認的關鍵架構決策

| 主題 | 決策 |
|---|---|
| FIDO 伺服器定位 | 多租戶平台，僅作後端驗證服務（非身分來源） |
| RP ID | 購物網站網域 |
| 身分權威來源 | 購物網站既有帳密系統；FIDO 為加掛選項，帳密永久保留（不可單獨用 FIDO 登入取代帳密救援） |
| FIDO 驗證 APP 角色 | 自訂 Android `CredentialProviderService`，掛載於系統 Credential Manager（非獨立 APP 跳轉、非推播）。**cross-device carve-out（情境三）**：另有一個由 deep link（已驗證 App Link）喚起的 `CrossDeviceLoginActivity`，供桌機 QR 掃碼登入使用。此為 `非獨立 APP 跳轉` 決策的明確 carve-out——該決策原意管的是**同裝置正常 ceremony 不得改走獨立 App**，而 cross-device 情境手機端根本沒有瀏覽器頁面/原生 App 在呼叫 Credential Manager（發起方在另一台桌機），Credential Manager 隱式喚起模型物理上不適用，deep link 是唯一可行喚起方式。**硬性範圍邊界**（比照 `SetupStatusActivity`）：此 Activity 只能經有效的、伺服器發出的 `xdevId` deep link 進入；只做「claim→確認→簽 assertion→submit」單一 ceremony；**嚴禁**新增裝置列表/撤銷/註冊等任何管理或並行認證 UI（那會違反 `非獨立 APP 跳轉` 原意）。同裝置流程不受影響、註冊仍只走 Credential Manager。詳見 `docs/decisions/qr-cross-device-login-design.md` 8.2 |
| FIDO 驗證 APP 啟動器畫面 | `prod` flavor 保留**一個**最小化「設定/狀態」啟動器 Activity（Option B）。範圍嚴格限定：顯示 provider 是否已在系統啟用、一顆深連結進入系統 credential-provider 設定的按鈕、版本/客服/隱私（個資法）文字。**嚴禁**在此畫面內做任何登入/註冊 ceremony 或裝置列表/撤銷 UI——那會使其退化為 `非獨立 APP 跳轉` 決策所禁止的並行認證/管理路徑。ceremony 一律仍走 Credential Manager（由系統以 PendingIntent 隱式啟動 `CreatePasskeyActivity`/`GetPasskeyActivity`）。詳見下方「啟動器畫面決策」 |
| 支援裝置 | 僅 Android 14+；不支援舊版 Android，第一版不支援 iOS |
| 金鑰保護 | 強制要求 TEE/StrongBox 硬體安全區，不通過則拒絕註冊；驗證 Android Key Attestation 憑證鏈 |
| 多裝置 | 允許使用者註冊多台裝置，提供管理介面 |
| 帳號救援 | 既有帳密為主，客服人工為後盾 |
| 憑證撤銷 | 使用者主動撤銷 + 簽章異常（sign counter 倒退）自動撤銷 |
| 裝置撤銷方式 | 軟刪除（`status=REVOKED`），不實體刪列，保留 1 年稽核 |
| API 風格 | 同步 REST API + 短時效自簽 JWT 做購物網站與 FIDO 伺服器的 session 交接 |
| API 版本策略 | URI 路徑版本 `/api/v1` |
| API 認證 Header | `X-API-Key`（必，決定租戶）+ 選用 `X-Tenant-Id`（交叉檢查）/ `X-Request-Id`（追蹤） |
| 租戶速率限制 | 每租戶 100 TPS，超過回 `429` + `Retry-After` |
| 正式租戶開通 | **已拍板，待 dev-engineer 實作**：採**本機 admin CLI**（Spring Boot `CommandLineRunner`，`admin-cli` profile 啟動、不起 web server、一次性執行後 `System.exit`），**不**開對外管理 REST 端點。理由見下方「租戶開通 / 簽章金鑰 CLI 決策」。`create-tenant` 指令由平台維運方在伺服器主機上執行，輸入租戶名/`rp_id`/`expected_origin`（可多個）/選填 `rate-limit-tps`，自動產生高熵 API Key、以既有 `ApiKeyService` 規則存雜湊+前綴、明碼金鑰**只印一次到 stdout**（提示安全轉交、絕不寫 log）。原生 App 綁定（`tenant_app_bindings`）**刻意分成獨立 `add-app-binding` 指令**、不併入 `create-tenant`（呼應 OB6 人工 onboarding、opt-in 的後續步驟）。`DevDataSeeder` 續留為**僅 dev** 用途（`fido.dev-seed.enabled` 預設 `false`），**上線前務必確認關閉**；正式開通一律走 CLI 不再人工 `INSERT`。 |
| Session JWT 演算法 | ES256（EC P-256），公鑰經 JWKS 端點提供 |
| Session JWT 有效期 | 120 秒 |
| Session JWT 簽章金鑰持久化 | **已拍板，待 dev-engineer 實作**：金鑰持久化到資料庫**第八張表 `signing_keys`**（見 `docs/db-schema.md` 第 10 節 / DB18），多實例連同一庫即天然共享同一把 `ACTIVE` 金鑰，解決重啟換鑰/JWKS 不一致缺口。私鑰以 PKCS#8 存 `VARBINARY`、由既有 TDE 保護（不加應用層封裝）。**啟動行為**：載入唯一 `ACTIVE` 金鑰；全新庫首啟則自動產生並 INSERT（filtered unique index `UX_signkey_one_active` 保證至多一把 ACTIVE，兼作多實例首啟並發競態防護，敗者改讀既有金鑰）；後續啟動一律載入不重生。**輪替**：v1 只做「持久化 + 單一有效金鑰」，schema 已預留 `status`/`retired_at` 使日後輪替免改表；不做自動排程輪替，僅提供 admin CLI `rotate-signing-key` 手動指令（因 JWT 僅 120 秒效期，重疊窗口極小，JWKS 一併發布 ACTIVE+RETIRED 公鑰即可平滑過渡）。理由與規格見下方「租戶開通 / 簽章金鑰 CLI 決策」。 |
| 防帳號列舉策略 | 登入與裝置管理 API 一律採 200 + 空清單 / 冪等 no-op，不用 404 洩漏使用者或裝置是否存在 |
| 傳輸安全 | TLS + API Key |
| WebAuthn origin 綁定 / 存取情境 | v1 provider 一併支援「瀏覽器存取」與「購物網站原生 App 直呼 Credential Manager」兩情境；原生 App 情境對每個租戶採 **opt-in**（租戶須完成 `assetlinks.json` Digital Asset Links onboarding + 平台登錄 App 簽章指紋至 `tenant_app_bindings`）。origin 由 provider 從呼叫方動態、經驗證取得（不寫死），伺服器以 `expected_origin`(web) ∪ `tenant_app_bindings`(app) 允許清單把關，不符回 `403 ORIGIN_NOT_ALLOWED`。詳見 `docs/origin-binding.md` |
| Challenge 時效 | 60 秒（同裝置情境 A），逾時前端自動重新申請。**cross-device 情境三例外**：該 ceremony type 的 challenge/xdev session TTL 放寬為 **120 秒**（多了拿手機→掃碼→看確認畫面→指紋數個人為步驟，60 秒偏緊），僅此 ceremony type 偏離、**不動同裝置 60 秒**，兩者並存不互相取代。 |
| 跨裝置 QR 登入 — 使用範圍限制（S7） | **限縮範圍**：QR 跨裝置登入只能用在一般瀏覽/購物等**低風險**情境；敏感動作（改密碼、金流、裝置撤銷/管理）**仍強制要求同裝置驗證**，不接受以 QR 登入取得的 session 去執行這些操作。落地機制：cross-device 簽發的 session JWT 於 `amr` claim 多帶 `"xdev"` 值（見下方「目前階段」cross-device 段落與 `docs/api-contract.md` §1.3），下游（購物網站後端）偵測到 `amr` 含 `"xdev"` 時，對敏感操作須要求 step-up（同裝置重新驗證），不可直接放行。 |
| 跨裝置 QR 登入 — proximity 政策（S2） | **預設只警示、不阻擋**：手機與桌機出口 IP 不一致時**不擋登入**，僅於 `audit_log`（`detail.proximityMismatch=true`）與確認/回應中標記異常，供稽核與客服排查。（與設計文件原建議的 strict 不同，擁有者拍板採警示制。）殘餘風險已由擁有者簽核（S1，風險責任轉由使用者承擔），並以 S7 範圍限縮為主要補償控制。 |
| 資料庫 | 獨立 SQL Server 實例（與其他系統分開）；九張核心表：`tenants`, `fido_user_ref`, `fido_credentials`, `bound_devices`, `auth_challenges`, `audit_log`, `tenant_app_bindings`, `signing_keys`, `cross_device_sessions`（第七張為原生 App 情境的 Digital Asset Links 授權登錄，見 `docs/origin-binding.md`；第八張為 session JWT 簽章金鑰持久化，見 `docs/db-schema.md` 第 10 節 / DB18；第九張為情境三跨裝置 QR 登入 session 的狀態機/雙方 IP/確認碼載體，見 `docs/db-schema.md` 第 11 節） |
| 加密/備份 | TDE 全庫加密 + 標準定期備份 |
| 容量目標 | 中小規模：數萬會員，峰值 ≤100 TPS |
| 部署 | 全地端部署（非雲端） |
| 法規範圍 | 適用台灣個資法，不涉金流相關法規 |
| 稽核紀錄保留 | 1 年 |
| FIDO Conformance Testing | 不在第一版投入，上線後再評估 |

## 目前階段

可行性評估與 22 項規格缺口已全數確認。REST API 合約（`docs/api-contract.md`）與核心表 DB schema（`docs/db-schema.md`，原六張、因 origin 綁定定案新增 `tenant_app_bindings` 後為七張）已定案；`fido-server` 後端骨架已建立，含真實 WebAuthn 密碼學驗證（簽章驗證、Android Key Attestation 憑證鏈驗證、TEE/StrongBox 判讀）、完整測試涵蓋率、JPA/Hibernate 持久層（本機開發用 H2，正式部署接 SQL Server，`infra/sql/` 已備妥建置腳本待正式環境套用）。

Android Credential Provider 技術驗證 PoC 的 10 項驗證項目已具體定案，見 `docs/android-poc-checklist.md`（含每項的關鍵/非關鍵標記、模擬器可驗證/須實機驗證標註、通過判準）。與原本「#1–5 為關鍵項目」的粗略說法相比，定案後的關鍵項目改為聚焦「地基掛載 + 硬體金鑰 + android-key 格式對接」（清單項目 1–5），**登入 ceremony 端對端重新歸類為非關鍵**（assertion 簽章與硬體無關、伺服器端已有測試覆蓋）。

開發環境目前沒有實體 Android 裝置，僅能用模擬器（Android 14 / API 34）驗證；PoC「通過」採**條件式通過（pending 實機）**：模擬器可驗證項目全數通過、關鍵風險已排除，即可先行推進正式開發時程，硬體聲明真偽與 OEM 相容性列為取得實機後的收尾驗證項目，不阻塞整體進度。

**（後續更新）「pending 實機」的核心懸念已在真實裝置上解除。** 取得一台實體 Pixel 9（Android 14）後，以真實 Chrome 瀏覽器（非模擬器、非直打 HTTP 略過瀏覽器層）完整走過一次註冊 → 登入 → 撤銷三個流程，過程中發現並修復三個先前模擬器/單元測試從未踢到的真實 bug（詳見下方 PoC 段落之後的紀錄）：(1) 手寫 CBOR 編碼器產出非 canonical 順序，Chromium 嚴格 CBOR 解碼器拒絕；(2) Chrome 原生層要求 `authenticatorData`/`publicKeyAlgorithm`/`publicKey` 三個額外欄位；(3) 最關鍵的一項——provider 忽略了瀏覽器身為 WebAuthn client 提供的 `clientDataHash`、自行重建 clientDataJSON 再雜湊簽章，導致簽章位元組與瀏覽器實際使用的不一致。三者修復後，`HardwareKeyManager` 記錄 `detectedLevel=STRONG_BOX`（真實硬體，非模擬器聲明）、`fido-server` 端 `attStmt.sig` 簽章驗證通過、`shopping-site-reference` 端真的驗證了 fido-server 簽發的 session JWT 並建立登入 session（有 log 佐證，非僅口頭回報）。**硬體安全等級真偽此一 PoC 遺留問題，在 Pixel 9 上已正面驗證。** OEM 多樣性（其他廠牌/機型是否一致）仍未涵蓋，留待後續視需要補測，不視為阻塞項。

**（後續更新，同一台 Pixel 9）多裝置管理與撤銷防護亦已在真機上驗證。** 同一使用者（demo-user-001）註冊兩台裝置（DeviceA、DeviceB，皆 `STRONG_BOX`），以真實 `列出我的裝置` 查詢確認兩者獨立列出；撤銷 DeviceA 後重新查詢，DeviceA 顯示 `REVOKED`、DeviceB 仍 `ACTIVE`，證實撤銷單一裝置不影響其他裝置。「撤銷後用該裝置登入應失敗」則透過程式碼比對確認為兩層防護：`AuthenticationService.buildOptions` 的 `allowCredentials` 只納入 `RecordStatus.ACTIVE` 的憑證（撤銷後的憑證連候選清單都進不去，系統選單因此不會列出它——真機實測結果與此完全一致：撤銷後的 DeviceA 確實從 Chrome 的憑證選單消失），`AuthenticationService.verifyResult` 另外在任何密碼學簽章驗證之前，先以 `credential.getStatus() != ACTIVE` 擋下並回傳專屬的 `CREDENTIAL_REVOKED` 錯誤碼，屬於縱深防禦。**過程中發現一個次要的既有缺口**：`CreatePasskeyActivity.performRegistration` 呼叫 `LocalCredentialStore.rememberCredential(...)` 的時機在「送到伺服器驗證之前」，導致任何在裝置端已產生金鑰、但伺服器端後來拒絕（例如 challenge 逾時、fmt 錯誤）的註冊嘗試，仍會在手機本地留下永久的「幽靈」憑證項目，長期累積會讓系統登入選單塞滿一堆選了必失敗的舊項目——不是安全問題（伺服器端 allowCredentials 範圍已確實把關），純粹是使用體驗上的技術債，列為已知待改善項目，不阻塞目前進度。

**PoC 執行完畢，結論：條件式通過（pending 實機）。** `android-credential-provider/` 專案已建立（`FidoCredentialProviderService` + StrongBox/TEE 金鑰產生 + 手寫 CBOR 組出 `fmt=android-key` attestationObject），關鍵項目 1–5 在模擬器上全數驗證通過，其中風險最高的項目 3（自訂 provider 能否產出伺服器可解析的 android-key 格式）已用**模擬器真實 Android Keystore 吐出的憑證鏈**（非僅 JVM 自簽測試 fixture）端對端送驗 `fido-server`（`fido.attestation.mode=real`）確認可行。硬體安全等級本身（StrongBox/TEE 是否真的達標）因模擬器無真實安全硬體，仍待實體裝置驗證；`fido-server` 新增 `fido.attestation.poc-trust.*` 設定（預設關閉、僅供 PoC 期間額外信任模擬器測試 root，不影響正式路徑的 Google root 信任集合）。詳細逐項結果見執行紀錄。原本待釐清的「原生 App 情境下的 origin 綁定架構」已由 systems-analyst 定案於 `docs/origin-binding.md`（OB1–OB6 六項決策經人類拍板，含新增第七張表 `tenant_app_bindings`、`ORIGIN_NOT_ALLOWED` 錯誤碼、稽核 originType、原生 App opt-in 範圍），並已回填 CLAUDE.md / db-schema.md / api-contract.md / infra/sql；devops-engineer 已完成 LocalDB 重新驗證含第七張表的 schema，dev-engineer 已完成 provider 動態 origin 解析與 server 端 origin 允許清單/JPA 實體。PoC harness 程式碼已用 Gradle product flavor（`prod`/`poc`）分離，正式建置產物不含診斷程式碼，此開放問題已解決。

購物網站串接參考範例（`shopping-site-reference/`）已建立，示範註冊/登入/裝置管理三個代理流程，核心為 `FidoSessionJwtValidator`（不信任回應 `verified` 欄位，只信任自行驗證過的 session JWT）。過程中發現的 IDOR 缺口（`externalUserId` 未限定為呼叫端已驗證的登入使用者）已修復：範例現在一律從購物網站自己的登入 session 取得 `externalUserId`，並已將此責任邊界明文寫入 `docs/api-contract.md`（D15）。**決策（systems-analyst，D15 後續）：請求 DTO 上選填的 `externalUserId` 相容欄位「刻意保留、不移除」。** 理由：`shopping-site-reference/` 是教學/參考範例，其產品價值就是向未來串接者示範正確安全模式；`ShopSessionService.resolveExternalUserId(session, claimed)` 的「夾帶不符即 403 `EXTERNAL_USER_ID_MISMATCH`」正是 D15 責任邊界的具體防禦示範，並已有單元測試與真實跨行程 E2E harness 兩層負向案例覆蓋。fido-server 上游 API 本身就在多個端點路徑/參數帶 `externalUserId`（§2.1/§2.2/§4.1/§4.2/§5.1/§5.2），D15 存在的原因正是伺服器無法代驗呼叫者本人，把關責任落在購物網站後端——移除本範例入站 DTO 的欄位是「迴避」而非「示範解決」該責任，且會使既有 IDOR 負向測試無案可測而需刪除，教學價值淨損失。此欄位在 DTO/controller Javadoc 已明確標示「不可信任、一律以 session 為準」，不會傳達錯誤示範。正式生產購物網站若其自家前端契約本就不帶此欄位，可自行不接受（型別層消除攻擊面），此為與範例並行的合法做法、不衝突。此決策不改變任何端點行為，`docs/api-contract.md` 無需更動。

`fido-server` 與 `shopping-site-reference` 之間已完成**真實跨行程端對端整合驗證**（qa-engineer 執行，人工複核 log 與 git 狀態）：兩個服務各自以獨立 JVM 啟動（非單一測試行程內的 `MockMvc`/`@SpringBootTest`），以真實 HTTP 走完「註冊（真實 StrongBox 等級 android-key attestation 憑證鏈+簽章）→ 裝置落庫確認 → 登入（真實 assertion 簽章）→ session JWT 簽發 → shopping-site-reference 端真實打 JWKS 端點驗證簽章並建立 SHOP_SESSION → 裝置列表/撤銷代理 → IDOR 反例（夾帶他人 externalUserId 應回 403 EXTERNAL_USER_ID_MISMATCH）」全流程，共 13 項檢查全數通過，證據與重現腳本見 `fido-server/src/test/java/com/fido/server/e2e/CrossProcessE2EManualRunner.java`（手動執行，未掛進 `mvn test`/CI）。Android Credential Provider 端因本機環境無已連接的模擬器/實機，此輪不含在範圍內，其結果仍以既有的條件式通過 PoC 紀錄為準。

### 啟動器畫面決策（`android-credential-provider` prod flavor 是否需要 launcher UI）

PoC 用 Gradle product flavor 把診斷 harness（含 `HarnessActivity`，唯一帶 `MAIN`/`LAUNCHER` 的元件）隔離到 `poc` flavor 後，`prod` flavor 變成**零啟動器 Activity**——純背景 `CredentialProviderService`。此為移除 harness 的副作用而非刻意設計，遺留為開放問題，由 systems-analyst 分析、人類拍板。結論：

- **三件事在既有架構下已明確不需要 app 端畫面**，headless 對它們是正確答案：
  1. **註冊/登入 ceremony 都不需直接開啟 app**——新裝置第一次註冊與後續登入走同一條路：購物網站（瀏覽器頁面或其原生 App）呼叫 Credential Manager `createCredential`/`getCredential`，由系統以 PendingIntent 隱式啟動 `CreatePasskeyActivity`/`GetPasskeyActivity`（見 `CreatePasskeyActivity.kt` 以 `PendingIntentHandler.retrieveProviderCreateCredentialRequest` 取請求），程式碼中沒有任何「使用者主動開 app 開始註冊」的路徑。
  2. **`多裝置…提供管理介面`需求已被雙重滿足**：系統 Settings → 密碼/憑證提供者頁面（任何 `CredentialProviderService` 免費取得）＋ 購物網站自家的 `DeviceProxyController`（`/shop/api/fido/devices` list/revoke，經購物網站已驗證 session 代理）。Android app **不需**自建裝置列表/撤銷 UI。
  3. **「目前使用哪個身分」指示器不適用**：本 app 不持有任何帳號/登入狀態，只依 `rpId` 存放 platform credential，無可顯示內容。

- **唯一未被既有決策決定的開放判斷點是「首次啟用 provider ＋ app 可被發現性」**：Android 14 新裝的 `CredentialProviderService` 不會自動啟用，使用者須到系統設定手動開啟；零啟動器時 app 不在 app drawer 出現，安裝後無處可點、無法深連結到設定、無法顯示「是否已啟用」。人類拍板採 **Option B**：保留**一個**最小「設定/狀態」啟動器 Activity（啟用狀態顯示＋深連結進系統設定的按鈕＋版本/客服/隱私文字），與 `非獨立 APP 跳轉` 決策相容（該決策管的是認證流程不得改走獨立 app，而非禁止設定畫面）。**硬性範圍邊界**：此畫面永遠不得長成任何登入/註冊 ceremony 或裝置管理 UI，一旦如此即違反 `非獨立 APP 跳轉`。

實作（把此 Activity 加進 `prod` 的 `src/main/AndroidManifest.xml` 並建畫面）由 dev-engineer 在其 OriginResolver/瀏覽器允許清單當前任務結束後另案承接，避免兩個 agent 同時建置同一 Gradle 專案；systems-analyst 本次僅落定決策文件、未動 Android 程式碼與 manifest。

`OriginResolver.kt` 受信任瀏覽器 allowlist 已擴充並完成人工複核：dev-engineer 逐一比對 Google 官方即時服務端點 `gstatic.com/gpm-passkeys-privileged-apps/apps.json` 與兩個獨立開源密碼管理器專案的自動同步結果，**發現舊版 Chrome 穩定版指紋是先前未經查證即寫入的錯誤值**（任何權威/第三方來源皆查無比對），已更正並擴充涵蓋 Chrome（穩定版/Beta/Canary）、Firefox、Samsung Internet、Edge、Brave；我已獨立重新 fetch 官方端點逐位元組比對全部指紋（含多簽章項目）確認吻合，並重跑過 `OriginResolverTest` 8 項測試確認通過。原生 App origin 解析路徑（`android:apk-key-hash:...`）也已補上先前缺的模擬器端對端驗證：新增獨立驗證用模組 `android-credential-provider/testcaller/`（與 `:app` 無依賴、不同簽章身分，明確標示「驗證專用、非產品程式碼」），在 `fido_poc_avd` 模擬器上以真實 `CallingAppInfo`/`SigningInfo` 框架物件驅動，provider 端記錄的解析結果與獨立計算值完全一致。

**啟動器畫面決策 Option B 已實作完成**：`SetupStatusActivity`（`android-credential-provider/app/src/main/java/com/fido/credentialprovider/ui/SetupStatusActivity.kt` + `SetupStatusSupport.kt` + `res/layout/activity_setup_status.xml`）已加入 `src/main/AndroidManifest.xml`，為 `prod`/`poc` 共用的 `MAIN`/`LAUNCHER` 元件（`poc` 額外合併 `HarnessActivity` 作第二個啟動圖示，不影響 `prod`）。嚴守範圍邊界，僅含啟用狀態、深連結系統設定按鈕、版本/客服/隱私文字，無任何 ceremony 或裝置管理 UI。兩個技術選型皆以 `javap` 反組譯 `android-34/android.jar` 與 `androidx.credentials:credentials:1.5.0` 位元組碼查證（非猜測）：(1) 啟用狀態查詢用 framework `android.credentials.CredentialManager#isEnabledCredentialProviderService(ComponentName)`（androidx 版本無此 API）；(2) 深連結沿用 androidx `CredentialManager.createSettingsPendingIntent()` 實作內部使用的同一組 `Intent("android.settings.CREDENTIAL_PROVIDER").setData(Uri.parse("package:"+套件名))`。已在 `fido_poc_avd` 模擬器上實機（模擬器）驗證：launcher 圖示可解析為 `SetupStatusActivity`、畫面正確渲染、按鈕深連結精準導向系統「Passwords & accounts > Additional providers > FIDO Authenticator」列（螢幕截圖確認）。**已知模擬器限制（非本次程式碼缺陷）**：`isEnabledCredentialProviderService` 呼叫在此模擬器映像上，無論查詢前後、也無論嘗試切換系統設定開關，皆從 Binder 端拋出解序列化失敗的 `NullPointerException`（`system_server` 端 `CredManSysService` log 已確認收到完全正確的 `ComponentName`，故排除呼叫端組錯參數的可能）；且系統設定開關本身在此模擬器上點擊後 log 顯示 `setEnabledProviders success` 但畫面/狀態未實際變成已啟用，研判是此 AVD 系統映像對第三方（非 Google 簽章）credential provider 的啟用管線限制或已知平台缺陷，非本 App 程式碼可修正。程式碼已針對此三態（true/false/null）容錯設計，查詢失敗會優雅降級為「無法判定，請至設定確認」文字並附測試（`SetupStatusSupportTest`，JVM 純字串邏輯，7 項全過）而不會使 App 崩潰；`enabled=true` 顯示路徑僅有單元測試涵蓋（`statusLabel(true)`），未能在此模擬器上端對端驗證，列為待實體裝置驗證項目（比照既有 PoC「條件式通過 pending 實機」精神）。**（後續更新，Pixel 9 實機驗證）**：在真實裝置上開啟本畫面，`isEnabledCredentialProviderService` 呼叫兩次（`onCreate`/`onResume` 各一次）皆正常回傳、無任何例外 log，畫面正確顯示「已啟用」，與使用者實際在系統設定看到的狀態一致。證實上述 `NullPointerException` 確實是模擬器平台限制，非本 App 邏輯缺陷；`enabled=true` 顯示路徑至此也完成端對端真機驗證。

### 租戶開通 / 簽章金鑰 CLI 決策（實作規格，dev-engineer 承接）

兩項先前僅在採用廠商文件揭露為「未解決」的缺口，經 systems-analyst 拍板為可實作決策（本段為交接規格）。技術限制手冊 `docs/vendor/technical-limitations.md` 第 9/12 項與維護手冊 `docs/vendor/maintenance-guide.md` 第 4 節目前措辭仍為「尚未持久化 / 僅腳本就緒」等**未解決**用語；**待 dev-engineer 實作並經 qa 驗證後，再由 dev-engineer 回頭把這兩份文件的用詞改為「已實作」**（systems-analyst 本次未動 `docs/vendor/*.md` 內文）。

**共用載體：admin CLI（`admin-cli` Spring profile）**

- 一個 Spring Boot `CommandLineRunner`（例如 `com.fido.server.admin.AdminCliRunner`），**僅在 `admin-cli` profile active 時**建立為 bean（`@Profile("admin-cli")`）。此 profile 另以 profile 專屬設定關閉 web server（`spring.main.web-application-type=none`）——即開通/輪替操作**不開任何網路端口**，只在伺服器主機本機以 `java -jar fido-server.jar --spring.profiles.active=admin-cli --fido.admin.command=<cmd> ...` 執行，跑完 `System.exit(code)`（成功 0、失敗非 0）。
- **為何 CLI 而非管理 REST 端點**：部署模型為全地端、操作者是平台維運方（賣方）本人、採用廠商只拿自己租戶的 API Key 不會也不需呼叫開通。CLI 以「能 shell 進主機」為隱含強認證，零新增攻擊面；反觀對外管理端點需另立一組平台管理金鑰（與其欲取代的人工密鑰管理形成雞生蛋問題）、需 IP allowlist/localhost 限制、且在此部署模型下對唯一合法呼叫者（有主機權限者）毫無便利性淨益，只擴大攻擊面。故一律 CLI，不開管理端點。
- CLI 共用既有 `ApiKeyService`、JPA repository、實體，**不得**手算雜湊或手寫 SQL。每個成功操作寫一筆 `audit_log`（`event_type` 為新增字串 `TENANT_PROVISIONED` / `TENANT_APP_BINDING_ADDED` / `SIGNING_KEY_ROTATED`，`outcome='SUCCESS'`；`audit_log.event_type` 為自由 `NVARCHAR(50)` 無 CHECK，**不需改 schema**），且**稽核列與 log 一律只記 `tenant_uid`/`api_key_prefix`，永不記明碼金鑰**。

**指令 1：`create-tenant`（缺口二）**

- 參數：`--fido.admin.tenant.name=`（必）、`--fido.admin.tenant.rp-id=`（必）、`--fido.admin.tenant.expected-origin=`（必，允許重複帶入多個或以 JSON 陣列字串表示，落庫格式對齊 `tenants.expected_origin` 現行「單一字串或 JSON 陣列字串」慣例）、`--fido.admin.tenant.rate-limit-tps=`（選填，預設 100）。其餘 `tenants` 欄位皆由預設/自動產生涵蓋（`tenant_uid`=NEWID、`status`=ACTIVE、`created_at`/`updated_at`），**無其他必填欄位**。
- API Key 產生：CLI 端產生高熵金鑰，建議格式 `fsk_` + `base64url(32 random bytes)`（前綴 `fsk_` 讓 `ApiKeyService.prefix` 取到的前 12 字含可辨識前綴）。以 `ApiKeyService.hash`/`prefix` 落庫雜湊+前綴，**不存明碼**。
- 輸出：明碼金鑰**只印一次到 stdout**（非 logger），以明確分隔區塊呈現租戶 `tenant_uid`/`name`/`rp_id`/`expected_origin`/`rate_limit_tps` 與 `API KEY`，並附「請立即透過安全管道轉交採用廠商、之後無法再查回、切勿寫入 log」警語。
- `rp_id` 已有 `UQ_tenants_rpid` 唯一約束；重複 `rp_id` 應被 CLI 攔為明確錯誤訊息（非堆疊追蹤）。

**指令 2：`add-app-binding`（缺口二的 OB6 後續步驟，刻意獨立）**

- **不併入 `create-tenant`**：呼應 `docs/origin-binding.md` OB6——原生 App 綁定是 opt-in、且在採用廠商完成 `assetlinks.json` 部署後才辦理的**後續人工 onboarding**，與租戶開通時序解耦。
- 參數：以 `--fido.admin.tenant.uid=`（或 rp-id）定位既有租戶 + `--fido.admin.app.package-name=` + `--fido.admin.app.sha256-fingerprint=`（十六進位或 base64）+ 選填 `--fido.admin.app.label=`。CLI 換算 `apk_key_hash_origin`（`android:apk-key-hash:<base64url(fingerprint)>`）寫入 `tenant_app_bindings`（對齊 db-schema.md 第 9 節欄位與 `UQ_appbind_tenant_pkg_fp`）。

**指令 3：`rotate-signing-key`（缺口一的手動輪替，非必跑）**

- 把現行唯一 `ACTIVE` 的 `signing_keys` 列改為 `status='RETIRED'`+`retired_at=now`，產生新 EC P-256 金鑰對以新 `kid` INSERT 為 `ACTIVE`。因 JWKS 一併發布 ACTIVE+RETIRED 公鑰，舊 `kid` 簽出、≤120 秒內的 JWT 過渡期仍可驗簽。**無自動排程輪替**；此指令僅供疑似金鑰外洩等需求時手動使用。

**缺口一：`JwtService` 改為持久化金鑰（正常執行期，非 CLI）**

- 新增 `signing_keys` 表（**已在** `docs/db-schema.md` 第 10 節 / DB18、`infra/sql/002_create_tables.sql`、`infra/sql/003_create_indexes.sql` 落定；dev-engineer 建對應 JPA 實體 + repository）。
- `JwtService` 建構時**不再** `generateKeyPair()` 於記憶體；改為：讀唯一 `ACTIVE` 列 → 以 `PKCS8EncodedKeySpec`/`X509EncodedKeySpec` + `KeyFactory("EC")` 還原 `KeyPair`，`kid`/公鑰以該列為權威。若無 `ACTIVE` 列則產生一組（`kid` 取 `fido.session-jwt.kid` 若非空、否則 `sk_<yyyyMMdd>_<短亂數>`）並 INSERT；多實例首啟並發時倚賴 `UX_signkey_one_active` filtered unique index，撞唯一鍵者改讀既有列（需捕捉 `DataIntegrityViolation` 後重讀，確保最終共用同一把）。
- `jwks()` 改為回傳**所有** `ACTIVE`+`RETIRED` 列的公鑰（`JwkSet` 已是 `List<Jwk>`，**介面不變**、`JwksController` 不需改）。
- `issue(...)` 的 JWT header `kid` 改用載入金鑰的 `kid`（不再直接讀 `fido.session-jwt.kid`）。`DevDataSeeder` 續留 dev-only、不受影響。

**（後續更新，dev-engineer 實作完成，pending qa-engineer 驗證）** 兩個缺口皆已依上述規格實作：

- 缺口一：新增 `SigningKey`（domain）/`SigningKeyRepository`（介面）/`SigningKeyEntity`+`SpringDataSigningKeyRepository`+`JpaSigningKeyRepository`（JPA 實作）/`InMemorySigningKeyRepository`（memory 模式，比照既有慣例；也讓 `fido.persistence.mode=memory` 下「同時最多一把 ACTIVE」語意與 JPA 一致，插入第二把 ACTIVE 會拋 `DataIntegrityViolationException`）。新增 `SigningKeyFactory`（EC P-256 金鑰對 + kid 產生，`JwtService` 首啟與 CLI `rotate-signing-key` 共用）。`JwtService` 已依規格改寫：建構時載入/首啟產生、`issue()`/`jwks()` 皆以資料庫金鑰為權威。**H2 測試 schema 的已知落差**：正式 SQL Server DDL 的 `UX_signkey_one_active` 是 filtered unique index（`WHERE status='ACTIVE'`），但 H2 2.2.224 剖析器不支援 `CREATE INDEX ... WHERE` 語法，測試用 `schema-h2.sql` 改用「計算欄位（`status='ACTIVE'` 時取值、否則 NULL）+ 一般 UNIQUE 約束」達成等價語意（ANSI SQL/H2 皆不視多個 NULL 為互相衝突），僅影響 `fido-server/src/test/resources/db/h2/schema-h2.sql`，未動 `infra/sql/002_create_tables.sql`/`003_create_indexes.sql` 的正式 DDL。
- 缺口二：新增 `com.fido.server.admin` 套件（`AdminCliRunner`/`AdminCliProperties`/`AdminCliException`）+ `application-admin-cli.yml`（`spring.main.web-application-type=none`）。三個指令皆依規格完成；**與本段原規格的一個落差**：`add-app-binding` 實際只支援 `--fido.admin.tenant.rp-id=` 定位既有租戶，未實作 `--fido.admin.tenant.uid=` 這個替代定位方式——交付時收到的細部規格明確只列 rp-id 一種，未提及 tenant uid 選項，故僅實作前者；如平台維運方實務上需要用 `tenant_uid` 定位（例如 rp_id 尚未取得或已變更的情境），需再請 systems-analyst 確認是否要補上，目前不算未完成，僅記錄此落差供後續參考。
- 測試：新增 `JwtServiceTest`（mock repository，涵蓋首次啟動產生/沿用既有 ACTIVE/併發衝突改讀既有列三種情境）、`InMemorySigningKeyRepositoryTest`、`JpaPersistenceH2FlowTest` 新增一個測試方法（對真實 H2 資料庫驗證 filtered-unique-index 等價約束真的擋下第二把 ACTIVE、輪替後 `findAll()` 正確回傳 ACTIVE+RETIRED）、`com.fido.server.admin.AdminCliRunnerTest`（三指令各自的正常路徑 + 主要錯誤路徑，使用 in-memory repository 而非 mock）。`mvn test` 54/54 全過。已用 `mvn spring-boot:run -Dspring-boot.run.profiles=admin-cli`（file-based H2，`-Dspring-boot.run.useTestClasspath=true` 借用 test-scope 的 H2 依賴）手動跑過 `create-tenant → add-app-binding → rotate-signing-key` 完整鏈路與 `create-tenant` 重複 `rp_id` 的錯誤路徑，皆行為正確、程序確實結束（成功 exit 0、錯誤 exit 1，且錯誤路徑只印一行清楚訊息、無 stack trace）。
**（後續更新，qa-engineer 獨立驗證通過）** qa-engineer 重新獨立執行 `mvn clean test`（54/54 全過，非僅信任自報數字），並額外做了七項對抗性驗證：(1) 發現 `JwtServiceTest` 的併發首啟保護測試是純 mock、未證明真實併發下有效，自行對真實 H2 補寫 8 條 thread 併發 INSERT 的測試，結果 `successCount=1 conflictCount=7`，證實 H2 schema workaround 在真實併發寫入下與正式 filtered unique index 語意等效；(2) 用獨立 JVM 進程搭配 `taskkill` 真正終止再重啟，確認兩次啟動的 JWKS `kid`/`x`/`y` 完全相同，證實真正持久化載入而非重新產生；(3) 用 `grep -rl` 掃過整個 `fido-server/` 目錄與 `audit_log` 資料表，確認明碼 API Key 只出現在 stdout、不落地到任何 log 或稽核紀錄，並以獨立算出的 SHA-256 雜湊比對 `tenants.api_key_hash` 逐位元組相符；(4) 三個 CLI 指令的成功/失敗路徑皆以獨立進程驗證 exit code 正確（成功 0、已知錯誤 1 且無 stack trace）；(5) 完整走一次「用舊金鑰簽發 JWT → 執行 `rotate-signing-key` → 用 JWKS 回傳的舊公鑰手動驗簽」，證實輪替後過渡期內舊 token 仍可驗證。結論：**兩項功能驗證通過，可以合入**。`add-app-binding` 僅支援 `rp_id` 定位（不支援 `tenant_uid`）經確認因 `rp_id` 本身即為唯一約束、不影響功能完整性，維持現狀不需補實作。附帶發現一項既有風險的佐證（非本次交付範圍）：`DevDataSeeder` 在 file-based DB 重啟且 `fido.dev-seed.enabled=true` 時會因租戶已存在而啟動失敗，印證前面「上線前務必關閉」提醒的必要性。

`docs/vendor/technical-limitations.md`（#12）、`docs/vendor/maintenance-guide.md`（§4）、`docs/vendor/environment-setup-guide.md`（第 6 節）、`docs/vendor/api-integration-guide.md`（第 7.2 節）的「未解決／人工 SQL INSERT」用詞已同步改為反映 admin CLI 已實作的現況。

### 桌機 QR 掃碼跨裝置登入（情境三）決策定案

先前列為「先設計、後實作」的桌機瀏覽器 QR 掃碼跨裝置登入，設計方案見 `docs/decisions/qr-cross-device-login-design.md`（systems-analyst，比照金鑰持久化/租戶開通 CLI 的節奏，先出設計文件、集中列出需擁有者拍板的 S1–S7）。**擁有者已於本次全數拍板**，結論：

- **S1（殘餘風險）＝接受**：擁有者明示「風險責任轉給使用者自行承擔」。cross-device QR 登入本質上比同裝置 WebAuthn 防釣魚弱一級（密碼學綁定擋不住即時人為中繼），此為 QR + 網路回報、無 BLE 近距路線的固有上限。
- **S2（proximity 政策）＝預設只警示、不阻擋**（與設計文件原建議的 strict 不同）：手機與桌機出口 IP 不一致時不擋登入，僅於 `audit_log` 與回應標記異常。**因此設計文件 5.2.3 原把 proximity 列為「主力防禦」的前提改變**——warn-only 下 proximity 退為偵測/稽核，防釣魚的主要補償控制改由 S7 範圍限縮承擔（見下方「需再確認事項」）。
- **S3、S4、S5、S6＝採納設計文件原建議**：新增情境三並對「非獨立 APP 跳轉」加 carve-out（S3）；新增第九張核心表 `cross_device_sessions`（S4）；新增「手機 App 直連 fido-server、以 `xdevId` capability 認證、不帶 X-API-Key」的呼叫方類別（S5）；cross-device ceremony 的 challenge/session TTL 放寬為 120 秒（S6，僅此 ceremony type、不動同裝置 60 秒）。
- **S7（使用範圍）＝採納限縮範圍**：QR 跨裝置登入只能用於低風險情境；敏感動作（改密碼、金流、裝置撤銷/管理）仍強制同裝置驗證。

**本次新補的落地機制（`amr` cross-device 標記，systems-analyst 這次任務新增、非設計文件原有）**：S7 需要一個技術機制讓下游能分辨「這個 session JWT 是不是走 cross-device QR 這條較弱路徑簽出來的」。決定沿用 `JwtService.issue(...)` 既有的 `amr`（Authentication Method Reference）claim：同裝置流程維持 `["fido","hwk"]`，**cross-device 流程改簽 `["fido","hwk","xdev"]`**（多帶 `"xdev"` 值，語意上仍是 FIDO + 硬體金鑰的真簽章，只是額外經 cross-device 通道）。下游（`shopping-site-reference` 或未來採用廠商後端）在自己的授權邏輯裡偵測 `amr` 含 `"xdev"` 時，對敏感操作要求 step-up（同裝置重新驗證），不可直接放行。**注意 fido-server 本身無法強制 S7**（它非身分來源、看不到下游「哪個動作算敏感」），故此機制本質是「伺服器誠實標記登入路徑強度、下游據以授權」，enforcement 落在下游——這與既有「終端使用者身分/授權把關由呼叫端負責」(D15) 的責任邊界一致。已寫入 `docs/api-contract.md` §1.3（JWT claims）與 §3.4（端點），及採用廠商文件 `docs/vendor/api-integration-guide.md`（step-up 提醒）。

**已回填的文件（本次 systems-analyst 執行，純文件、未寫 production code、未 commit）**：本 `CLAUDE.md`（情境三敘述、決策表四列、APP 角色 carve-out、本段）；`docs/api-contract.md`（§3.4 四端點 A–D、§1.2 手機 capability 呼叫方類別、`XDEV_*` 錯誤碼、proximity 改警示欄位而非拒絕碼、§1.3 `amr` `xdev`、`originType` 擴充 `CROSS_DEVICE_QR`）；`docs/db-schema.md`（第 11 節 `cross_device_sessions`、核心表八→九張）；`docs/origin-binding.md`（OB7 cross-device origin 信任模型）；採用廠商四份文件（`technical-limitations.md` 第 2 項改寫、`api-integration-guide.md` 新增 §11、`usage-scenarios-guide.md` 新增情境九、`maintenance-guide.md` 新增 §11）。

**S1+S2(warn-only) 實際風險敞口已向擁有者澄清並二次確認（已結案，非開放問題）**：systems-analyst 主動指出，設計文件 S1 描述的殘餘風險窄帶（「同一網路出口的即時中繼 + 使用者對正確服務名仍確認」）其實是**在 proximity 為 strict 的前提下**才成立——strict 能攔下「攻擊者與受害者出口 IP 不同」的遠端中繼，殘餘只剩同出口。擁有者同時選了 **S2＝warn-only**，等於**放行了原本 strict 會攔下的遠端（不同出口 IP）即時中繼**，只留稽核痕跡；也就是 S1+S2(warn-only) 的實際殘餘風險**比 S1 條文字面描述更寬**：任意網路位置的即時人為中繼都不再被阻擋，全靠 (a) 手機確認畫面上使用者的警覺、(b) S7 範圍限縮把 QR session 能造成的損害限制在低風險動作。此落差已明確呈報擁有者，**擁有者在完整知悉此一更寬風險敞口後，再次明確確認「使用者自行承擔風險」，維持 S2＝warn-only 不變**。`docs/vendor/maintenance-guide.md` §11 已註記「未來若某租戶想要更強防護，可擴充為每租戶可設定 strict」為後續可擴充項（本次不實作該設定機制）。

**交接**：devops-engineer（`infra/sql/` 新增 `cross_device_sessions` 建表/索引/清理 Job、LocalDB 重驗含第九張表）；dev-engineer（fido-server 端點 A–D + JPA 實體 + 重用 `verifyResult` + 狀態機 + proximity 警示、`amr` 加 `xdev`；shopping-site start/poll 代理 + JWT 收尾 + `amr` step-up 授權示範；`CrossDeviceLoginActivity` + App HTTPS client）；qa-engineer（proximity 警示情境、`amr` 含 `xdev` 時敏感操作被要求 step-up 的授權情境、狀態機一次性/重放負向測試、跨行程 E2E）。

**（後續更新，2026-07-24）三個並行實作任務完成後浮現的兩個缺口，systems-analyst 已處理：**

- **缺口一（DENIED 無觸發端點）＝拍板新增端點 E（`docs/api-contract.md` §3.4.E / D18）。** 兩個獨立 dev-engineer 任務都誠實回報：原 §3.4 A–D 四端點沒有任何路徑能把 session 轉成 `DENIED`（fido-server 的 `CrossDeviceSessionStatus` 有此 enum 值、狀態機支援，但無 API 進入點；Android `CrossDeviceLoginActivity` 的「取消」與「本裝置無憑證」分支目前不呼叫伺服器、只結束畫面讓 session 自然逾時成 `EXPIRED`）。**決策＝新增第五個端點 `POST .../sessions/{xdevId}/deny`**（手機直連、`xdevId` capability，把 `PENDING`/`SCANNED`→`DENIED`＋寫 `audit_log XDEV_DENIED`）。理由：(1) `DENIED`（使用者看過確認畫面主動拒絕，尤其「不是我」）與 `EXPIRED`（被動逾時，資訊量低）語意不同，混為一談會流失訊號；(2) 在 S2 warn-only、遠端即時中繼不再被 proximity 阻擋的風險態勢下，使用者於確認畫面上的警覺是少數補償控制之一，`DENIED`+audit 正是讓「使用者主動識破並拒絕」被營運方觀察到的唯一途徑，對偵測釣魚/中繼有實質價值；(3) 這**不是新架構決策**——設計文件 §5.2.3 第 3 點已把確認畫面「不是我」明確拒絕出口（`DENIED`+audit）列為【必要】的四層防禦之一、屬 S1/S2 簽核範圍，且 §3.4.D 桌機輪詢的 `DENIED` 回應路徑本已定義（端點 D 無需改動），端點 E 只是補上被半定義的合約缺口。已回填 `docs/api-contract.md`（§3.4 端點表 A→E、§3.4.E 子節、§1.2.2/D16 手機直連端點由 B/C 增為 B/C/E、§6 對應總表、附錄 D18）。**後續小任務**：dev-engineer 補 fido-server 端點 E handler（`CrossDeviceLoginController` + service，複用既有 `xdevId` capability 解析）與 Android `CrossDeviceLoginActivity` 的取消/無憑證分支改呼叫此端點；`shopping-site-reference` 無需改動（poll 端點 D 的 DENIED 處理本已依合約實作）。

- **缺口二（端點 C→D 之間 session JWT 以單機記憶體 `Map<xdevId,IssuedToken>` 暫存）＝【已拍板，待 devops/dev-engineer 實作】改為持久化到 `cross_device_sessions`。** 背景：fido-server 實作者發現此暫存在多實例、端點 C 與端點 D 被路由到不同節點時，輪詢會拿不到 JWT（回 409、使用者體驗像卡住）。**擁有者已確認 `fido-server` 未來將部署在容器上、以動態調節 pod 數量的方式水平擴充**——即端點 C（手機確認/簽發）與端點 D（桌機輪詢/領取）極可能落在不同 pod，記憶體暫存必然讀不到。這**不是新架構決策**，而是情境三設計時即預告的「最乾淨作法」之執行（同 DB18 金鑰持久化的拍板層級：擁有者拍板的是「多實例部署」這個上游決策，欄位/儲存機制屬 systems-analyst 可定的技術細節，無需再問擁有者）。與 `RateLimitService` 的差異也已釐清：`RateLimitService` 多實例是「軟性過度放行」（功能不壞），此 JWT 暫存是「功能性中斷」，故不能比照「接受單機限制」，必須修。
  - **拍板方案（systems-analyst，已回填 `docs/db-schema.md` 第 11 節 / DB20）**：`cross_device_sessions` 新增 `issued_jwt NVARCHAR(4000) NULL`（存端點 C 簽發、待端點 D 領取的完整 compact JWS；長度 4000 覆蓋最壞情況 `cid`=1024-byte credential id base64url 的 ≈3.2KB JWT，為最大非 MAX NVARCHAR，超出時 row-overflow 自動處理）。既有 `issued_jti` **保留**（供不解 JWT 即可查 jti 的稽核慣例，與現行 §3.4.C 稽核一致），非二擇一。
  - **端點 C（`submitResult`）改動**：移除 `pendingTokens.put(...)`；改在轉 `CONFIRMED` 時把 `token` 一併寫入 `issued_jwt`（連同既有 `issued_jti`）。移除欄位 `pendingTokens` 與 `JwtService.IssuedToken` 記憶體 Map。
  - **端點 D（`pollStatus`/`consumeConfirmedSession`）改動**：不再 `pendingTokens.remove(...)`；改以**守衛式（條件）UPDATE 重建原子性**——原記憶體 `Map.remove()` 天生保證「只有一個呼叫拿到 token」，落庫後必須用 `UPDATE ... SET status='CONSUMED', consumed_at=now, issued_jwt=NULL, updated_at=now WHERE xdev_pk=? AND status='CONFIRMED'` 並檢查受影響列數：**=1 才回傳先前讀到的 JWT；=0 表示已被其他併發輪詢/其他 pod 搶先領走 → 回 `409 XDEV_SESSION_INVALID_STATE`**（等同「已 CONSUMED」）。等價作法為 JPA `@Version` 樂觀鎖或 `SELECT ... FOR UPDATE` 行鎖，擇一即可。**領取後 `issued_jwt` 一併清為 NULL**（最小化 bearer token at-rest 窗口，jti 留供稽核）。原 `consumeConfirmedSession` 內「CONFIRMED 但 token 不在本實例（single-instance in-memory holder limitation）」的 409 分支連同其 Javadoc 一併移除——落庫後此失效模式不復存在。
  - **對外合約不變**：端點 D 的回應（`session.token`/`tokenType`/`expiresIn`/`warnings`）與儲存機制無關，`docs/api-contract.md` §3.4.D 無需改動（已於 db-schema §11 註明）。
  - **後續任務銜接**：devops-engineer 於 `infra/sql/002_create_tables.sql` 的 `cross_device_sessions` 建表加一欄 `issued_jwt NVARCHAR(4000) NULL`（在 `issued_jti` 後）、同步 `fido-server/src/test/resources/db/h2/schema-h2.sql`、於 LocalDB 重驗；dev-engineer 依上述改 `CrossDeviceSession` JPA 實體（加 `issuedJwt` 欄位映射）、`CrossDeviceLoginService`（端點 C 寫入、端點 D 守衛 UPDATE 領取）、`CrossDeviceSessionRepository`（加守衛式更新方法或改用樂觀鎖）；qa-engineer 補「多執行緒併發輪詢同一 CONFIRMED session 只有一個拿到 JWT、其餘 409」的負向測試（比照金鑰持久化那次以真實 H2 併發驗證守衛約束）。**清理排程無需改動**（`issued_jwt` 隨列一併清理，`infra/sql/006_retention_cleanup_jobs.sql` 不變）。

## 待辦事項

**桌機 QR 掃碼跨裝置登入（情境三）已拍板，待實作**（設計與決策見上方「桌機 QR 掃碼跨裝置登入（情境三）決策定案」段落）：

- **devops-engineer**：`infra/sql/` 新增第九張表 `cross_device_sessions` 的建表/索引/清理 Agent Job（比照 `auth_challenges` 過期標記+每日清理）；在 LocalDB 重新驗證含第九張表的完整 schema 建置；同步更新 H2 測試 schema。
- **dev-engineer**：fido-server 五個新端點（`docs/api-contract.md` §3.4 A–E，端點 E `.../deny` 為缺口一新增，見上方「後續更新」段落）+ `cross_device_sessions` JPA 實體 + 狀態機（PENDING→SCANNED→CONFIRMED→CONSUMED / DENIED / EXPIRED）+ proximity 警示（**只警示不擋、寫 `audit_log.detail.proximityMismatch`**）+ assertion 驗證重用 `AuthenticationService.verifyResult` + `JwtService` cross-device 路徑 `amr` 加 `"xdev"`；`shopping-site-reference` start/poll 代理 + 重用 JWT 收尾 + **示範偵測 `amr` 含 `xdev` 時對敏感操作要求 step-up 的授權邏輯**；`android-credential-provider` 新增 `CrossDeviceLoginActivity`（deep link 喚起、App 直連 fido-server、重用既有 assertion 簽章邏輯，取消/無憑證分支呼叫端點 E，嚴守 carve-out 範圍邊界）。
  - **注意（實作進度）**：A–D、shopping-site 代理、`CrossDeviceLoginActivity` 主流程已由三個並行任務完成並各自測試通過（82/60/53）；上述本行保留為完整需求索引。**尚未完成、需後續小任務銜接的是端點 E**：fido-server 加 `.../deny` handler（`CrossDeviceLoginController` + service，複用 `xdevId` capability 解析與狀態機轉移）、Android 取消/無憑證分支改呼叫端點 E。`shopping-site-reference` 無需改動。
  - **注意（缺口二：session JWT 落庫，已拍板待實作）**：`cross_device_sessions` 新增 `issued_jwt NVARCHAR(4000) NULL`，取代 `CrossDeviceLoginService.pendingTokens` 記憶體 Map（因擁有者確認容器化水平擴充、端點 C/D 可能跨 pod）。詳細規格見上方「缺口二」段落。銜接：devops-engineer（`infra/sql/002` 加欄＋H2 schema 同步＋LocalDB 重驗）、dev-engineer（實體 `issuedJwt` 映射、端點 C 寫入、端點 D 守衛式 UPDATE 領取並清 NULL、移除記憶體 Map 與其 409 分支）、qa-engineer（併發輪詢至多一個拿到 JWT 的負向測試）。
- **qa-engineer**：新增 proximity 不符「警示但放行」情境、`amr` 含 `xdev` 時敏感操作被要求 step-up 的授權情境、狀態機一次性/重放/跨 session 混淆負向測試、跨行程 E2E（比照 `CrossProcessE2EManualRunner`）。

上述兩項缺口（Session JWT 簽章金鑰持久化、正式租戶開通 CLI）已完成實作並經 qa-engineer 獨立驗證通過，相關採用廠商文件用詞已同步更新。

先前列出的三項（`shopping-site-reference/` CSRF 防護、session cookie
`secure` 預設值、`android-credential-provider/` 啟動器畫面 Option B 實作）皆已完成，過程與結果
記於上方「目前階段」相關段落（CSRF/cookie 見購物網站串接參考範例段落；啟動器畫面見「啟動器畫面決策」
段落）。`externalUserId` DTO 欄位一項則是拍板保留、非「尚未處理」，理由同見上方段落。

CI pipeline（原本盤點出的缺口之一）已建立，見下方「CI pipeline」段落。SQL Server 正式環境套用
（`infra/sql/` 腳本尚未在真正的 SQL Server 實例上驗證過）因目前無可用環境，暫緩處理，非本次範圍。

**採用廠商文件試跑（adopting-vendor-engineer 模擬客戶視角）盤點出 22 項卡點，systems-analyst 已處理**：19 項為文件矛盾/範例缺漏/格式未定義，已直接修正對應 `docs/vendor/*.md`（含最嚴重的「JWT 金鑰是否持久化」三份文件互相矛盾 #1、`maintenance §9` 自相矛盾 #2/#21、`xdevId` vs `qrUrl` 安全語意疑點 #15，及 #3–#5/#7–#13/#16–#19/#22 各項；已對照 `JwtService`/`SigningKeyFactory`/`AdminCliRunner`/`FidoSessionJwtValidator`/`db-schema` 查證後才寫，未憑空編）。以下 3 項屬產品/機制**增強**（非文件即可解、非阻塞），列為低優先待辦：

- **（低優先）採用廠商無自助管道確認「自己取得的版本是否含情境三（跨裝置 QR）等功能」（原報告 #6 / #14）**：目前唯一途徑是「向平台營運方確認」（文件已如此指引）。可評估的增強：於 `/actuator/info` 揭露 build 版本與 feature flag（例如 cross-device 是否啟用），讓採用廠商可自助查詢。屬產品決策，需先評估是否要對外揭露功能開關資訊（資安面）。
- **（低優先）跨裝置 `DENIED` 細部原因不回傳桌機（原報告 #18 的增強面）**：現行行為（`denyReason` 僅寫 `audit_log`、桌機只看到通用 `DENIED`）為**刻意**避免向桌機洩漏「該手機是否已註冊本站憑證」，已在 `api-integration-guide §11.2` 誠實記為限制。若未來有租戶明確要求對「使用者主動取消」vs「本機無憑證」給不同桌機文案，需 systems-analyst 評估「回傳粗粒度原因是否引入列舉風險」後再定，屬合約層變更。
- **（低優先）備份檔清理 / 異地備援僅有文字建議、無可直接套用的腳本範本（原報告 #20）**：`maintenance §2.3` 已給最低保留建議（FULL 5 份 / DIFF 14 天 / LOG 8 天）與方向（Maintenance Plan 或 OS 排程），但未附實際 PowerShell/robocopy/`sp_delete_backuphistory` 範本。可由 devops-engineer 於 `infra/sql/` 或 `docs/vendor/` 補一份可調整的清理＋異地同步腳本範本。

（另：`maintenance §11.3` 已載明的「每租戶 proximity strict 政策」仍為既有的未來可擴充項，不重複列。）

新的待辦事項出現時，請沿用既有慣例：在此列出簡短條目，完成後移除並把細節併入「目前階段」對應段落，
不要讓已完成項目長期留在本清單。

### CI pipeline

`.github/workflows/ci.yml` 已建立，四個平行 job：`fido-server`（`mvn test`，36/36 通過）、
`shopping-site-reference`（`mvn test`，43/43 通過）、`android-unit-tests`（Gradle JVM 單元測試，
`testProdDebugUnitTest`/`testPocDebugUnitTest` 皆跑，27/27 通過——目前 prod/poc 共用同一份
`src/test`，尚無 flavor-specific 測試，兩個 task 現階段會跑到同一組測試，兩者都跑是為未來新增
flavor-specific 測試預留涵蓋）、`cross-process-e2e`（建置兩邊 jar 後執行既有的
`CrossProcessE2EManualRunner`，真的起兩個獨立 JVM 跑一次完整註冊→登入→JWT 驗證→裝置管理→IDOR
反例流程，15 項檢查全過）。`android-credential-provider/testcaller/` 模組刻意不排入 CI（只有
`src/main`、無 `src/test`，本質是人工驅動的驗證用 APK，非自動化測試目標）。SQL Server 相關
（`infra/sql/`）不在此 CI 範圍內。devops-engineer 建置、qa-engineer 獨立重跑全部驗證項目複核
（含發現並修正一處 YAML 註解與實際原始碼不符：宣稱 prod/poc 測試集不同，經查證兩者其實共用同一份
`src/test`，已更正註解措辭，不影響 CI 行為本身）。

## 團隊分工（Claude Code Subagents）

本專案設定四個 project-level subagent（`.claude/agents/`），對應軟體開發流程中的四個角色：

- **systems-analyst**：架構決策、規格釐清、設計文件（API 合約、DB schema、流程圖）
- **dev-engineer**：Spring Boot 後端、Android Credential Provider APP、購物網站串接程式碼
- **devops-engineer**：SQL Server 建置、TDE/備份、部署腳本、CI
- **qa-engineer**：測試撰寫與執行、對照驗收標準回報結果

任何新的架構決策一旦拍板，請更新這份 CLAUDE.md，讓四個 subagent 在下次被呼叫時都能讀到一致的上下文（每次呼叫都是全新 context，不會記得先前對話）。
