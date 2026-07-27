# fido-server 管理後台（admin web UI / dashboard）— 必要性評估

> **狀態：已拍板（2026-07-27）——擁有者同意採納本文建議的【方案 A】，並要付諸實作。**
> 拍板內容：(1) 維持 CLI-only、**不做**任何 web 管理後台（方案 B 唯讀 UI 維持「觸發式再議」、方案 C 不採）；
> (2) 開通 Actuator `metrics` **＋少量非個資業務指標**；(3) admin CLI 新增唯讀 `list-tenants`；(4) 跨租戶裝置總覽不做。
> **重要落地限制（拍板時新增，本文 §2 方案 A 第 1 點原本只寫「綁內網/localhost」）**：查證發現 `ApiKeyAuthFilter.PUBLIC_PATH_PREFIXES`
> 含 `"/actuator"` 且專案**無 Spring Security**，`/actuator/**` 對任何能連到 8443 的人都免認證；而情境三要求手機 App
> 直連 8443，故該端口不可假設為內網。因此 metrics **必須**改置於獨立的 `management.server.port`，不得只在主端口加
> `exposure.include`。完整實作規格（含 metric 名稱/標籤硬規則、`list-tenants` 欄位與禁印項、連帶要改的 E2E harness 與
> vendor 文件）見 `CLAUDE.md`「待辦事項」的 dev-engineer 交辦條目與「管理後台 / 客戶 Portal 評估拍板結果」段落。
> 本文以下內容維持撰寫時的原貌（評估過程紀錄），不再改寫。
>
> 撰寫：systems-analyst｜日期：2026-07-25｜對照原始碼版本：`main` @ 75ed0f4｜拍板回填：2026-07-27

---

## 0. 問題與現況（已查證）

擁有者提問：除了既有的本機 admin CLI（`create-tenant` / `add-app-binding` / `rotate-signing-key` 三個一次性/低頻的**具狀態變更特權操作**）之外，有沒有必要再做一個管理後台？想像中的功能包括租戶清單瀏覽、稽核紀錄查詢、即時監控/告警、跨租戶裝置總覽、客服協助工具。

現況查證（原始碼 / 設定）：

- **既有管理操作＝CLI-only**：`com.fido.server.admin.AdminCliRunner`（`@Profile("admin-cli")`），啟動時 `spring.main.web-application-type=none`（不開任何網路端口），跑完 `System.exit`。決策理由見 `CLAUDE.md`「租戶開通 / 簽章金鑰 CLI 決策」段落，且**明確拍板不開對外管理 REST 端點**。
- **可觀測性地基已存在但未開通**：`fido-server/pom.xml` 已含 `spring-boot-starter-actuator`；但 `application.yml` 目前 `management.endpoints.web.exposure.include: health,info`、`health.show-details: never`——**metrics / Prometheus 端點尚未曝露**，也還沒有任何自訂業務指標（rate limit 命中、簽章失敗、proximity mismatch、自動撤銷）。
- **稽核資料含個資，屬個資法範圍**：`audit_log`（`docs/db-schema.md` §8）欄位含 `tenant_id`/`user_ref_id`/`ip_address`/`detail`(JSON，含 proximityMismatch、偵測到的 security_level 等)，1 年保留。目前**唯一查詢途徑是直接查 DB**，無任何 UI。`event_type` 為自由 `NVARCHAR(50)`（無 CHECK），已累積 `TENANT_PROVISIONED` / `SIGNING_KEY_ROTATED` / `XDEV_DENIED` / `AUTH_FAIL` / `AUTO_REVOKE_COUNTER_REGRESSION` 等值。
- **rate limit 無視覺化**：每租戶 100 TPS、超過回 429 + `Retry-After`（`CLAUDE.md` 決策表），但命中率/趨勢無任何監控。
- **裝置管理無平台層總覽**：裝置 list/revoke 走購物網站代理 `/shop/api/fido/devices`（`DeviceProxyController`），平台維運方看不到跨租戶總覽。

---

## 1. 關鍵判斷：既有「CLI-only、不開對外管理端點」的理由，對新需求是否仍適用？

既有決策的三個支柱（`CLAUDE.md`）：(a) 全地端部署、操作者是平台維運方本人；(b) 能 shell 進主機＝隱含強認證，零新增攻擊面；(c) 開對外端點要另立一組平台管理金鑰，形成「用來取代人工密鑰管理的東西自己又需要密鑰管理」的雞生蛋問題，且對唯一合法呼叫者無便利性淨益。

**這三個支柱是針對「具狀態變更的特權寫入操作」提出的。新需求需要按性質拆成三類分別判斷，不能一概而論：**

### 1.1 純觀測指標（rate limit 命中率、簽章失敗率、proximity mismatch 趨勢、撤銷異常）— 本質不同

- 這類是**聚合數值指標，不含個資**（是計數/比率，非某使用者的某筆紀錄）。
- CLI 與 ad-hoc SQL **都不適合**這個需求：需要的是「持續時間序列 + 趨勢 + 告警」，不是一次性查詢。這是**既有 CLI-only 決策完全沒有涵蓋、也無法涵蓋的真實缺口**。
- 因為不含個資、可綁定內網/localhost 抓取，**攻擊面與 PII 外洩風險遠低於任何觸及 `audit_log` 的方案**。支柱 (c) 的雞生蛋問題對「內網 Prometheus 抓 metrics 端點」影響很小（可用網段限制/mTLS，非平台級特權金鑰）。
- **結論：對這類需求，既有理由不再是反對理由；反而現況是個未填的缺口。**

### 1.2 唯讀業務資料查詢（租戶清單、稽核紀錄篩選、客服查特定使用者失敗紀錄）— 理由**大部分仍適用**，但多一層個資法考量

- 支柱 (b) 仍成立：**能查這些資料的人，本來就得能 shell 進主機或連到 DB**——他手上已經有 DB 存取權，一個指向 DB 的 BI/SQL 工具即可滿足唯讀查詢，**零新增網路攻擊面**。
- 支柱 (c) 仍部分成立：任何「經 web 曝露、需登入的唯讀查詢 UI」仍要一組登入憑證與其生命週期管理，仍是先前刻意避開的東西——只是唯讀時風險略低於寫入。
- **但新增一個 CLI 決策當時未被凸顯的考量：個資法。** `audit_log` 含使用者參照與 IP。把它做成 web 查詢 UI，等於**新開一條個資外洩路徑**（UI 被攻陷/設定失誤/越權查詢）。唯讀不代表低風險——**讀的正是個資**。這使得「唯讀 UI」的風險不像直覺以為的那麼低。
- **結論：唯讀 vs. 寫入確有本質差異（無特權狀態變更風險），但因觸及個資，既有「避免新增攻擊面」的精神仍強烈適用。** 傾向用「操作者既有的 DB/host 存取 + BI/SQL 工具」滿足，而非新建 web UI。若查詢真的頻繁到 SQL 難以負荷，再考慮嚴格限縮的唯讀 UI（見方案 B），且需擁有者對個資/攻擊面權衡簽核。

### 1.3 跨租戶裝置總覽 — 需求真實性存疑，且與定位有張力

- `CLAUDE.md` 定位 fido-server 為「**僅作後端驗證服務、非身分來源**」，裝置管理刻意交由購物網站代理。做一個「平台維運方跨租戶看所有人裝置」的總覽，會讓平台持有更集中的個資視圖，與「非身分來源」的定位及個資最小化原則有張力。
- 真正的營運需求（某裝置是否異常、撤銷是否生效）多半可由 §1.1 的聚合指標或 §1.2 的針對性稽核查詢覆蓋。**建議此項不單獨立案**，除非有明確營運場景證明必要。

**小結：新需求不是單一問題，而是三類。可觀測指標是真缺口且與既有理由本質不同（該做）；唯讀業務查詢既有理由大部分仍成立且多了個資法考量（傾向不新建 UI）；跨租戶裝置總覽需求存疑（傾向不做）。**

---

## 2. 方案比較

先點出一個**低估的第四選項**：既有 admin CLI 是可延伸的。要「租戶清單」不必然要 web UI——加一個 `list-tenants`（及必要時 `query-audit`）唯讀 CLI 指令，**完全沿用已拍板、已驗證的 CLI 模式，零新增攻擊面、零新登入憑證**，即可補上「看清單只能查 DB」的痛點。下方各方案會把此納入考量。

### 方案 A：維持現狀 + 補齊既有工具（CLI 唯讀指令 + BI/SQL 工具 + 開通 metrics 觀測）

不新建任何 web 應用。三個動作分別對應三類需求：

1. **可觀測指標**：開通既有 actuator 的 `metrics`/`prometheus` 端點（**綁內網/localhost，非公開**），由地端 Prometheus 抓取、Grafana 呈現與告警。若要 rate limit 命中、簽章失敗、proximity mismatch 等業務指標，dev-engineer 於 `RateLimitService`/`AuthenticationService`/`CrossDeviceLoginService` 加 Micrometer counter（附加式、小改動）。
2. **唯讀業務查詢**：租戶清單加 `list-tenants` CLI 指令；複雜稽核篩選/客服查詢用 DBA 既有的 SQL 或唯讀 BI 工具（SSMS / 唯讀帳號 + Power BI 之類）直查 DB。
3. **跨租戶裝置總覽**：不做（見 §1.3）。

- **優點**：與既有 CLI 決策**完全相容**（是它的自然延伸）；`audit_log` 個資**不經任何新 web 路徑曝露**，PII 攻擊面近乎零；metrics 端點不含個資、風險低；Grafana 告警是標準地端可觀測方案，開箱即用；成本最低。
- **缺點**：客服/稽核查詢要會下 SQL 或懂 BI 工具，非技術人員門檻較高；Grafana/Prometheus 是需維運的額外元件（雖為業界標準）；沒有「一個畫面看全部」的整合感。
- **實作成本量級**：**小**。actuator 曝露＝改設定；Micrometer counter＝少量附加程式碼；`list-tenants`＝複用既有 `AdminCliRunner` 骨架；Grafana/Prometheus＝devops 佈署既有工具，不寫程式。
- **安全性/攻擊面**：**最低**。無新增對外認證端點；metrics 走內網；業務查詢沿用既有 DB 存取權。
- **與 CLI 決策相容性**：**完全相容**。

### 方案 B：唯讀監控儀表板 web UI（無任何寫入/CRUD）

新建一個**只讀**的 web 後台：租戶清單、稽核查詢/篩選、指標圖表整合在一個登入後畫面。**明確不含**任何 create/rotate/revoke 等狀態變更（那些仍走 CLI）。

- **優點**：非技術營運/客服人員可用；查詢與趨勢整合在一處，體驗最好；仍守住「特權寫入操作留在 CLI」的既有決策核心。
- **缺點**：**重新引入既有決策刻意避開的東西**——一組平台管理登入憑證及其生命週期（支柱 c 的雞生蛋問題在此復活，只是唯讀版）；**`audit_log` 個資經 web 曝露**，必須嚴格限縮（僅內網/VPN/mTLS、強認證、UI 本身的存取也要寫進 `audit_log`、查詢要防越權與過量匯出）；UI 需長期維護（框架/相依安全更新）。
- **實作成本量級**：**中**。需前後端、認證、查詢 API、網段限制、對 UI 存取本身的稽核。
- **安全性/攻擊面**：**中～高**。新增一條觸及個資的認證端點，是本評估中 PII 風險最高需認真設計的方案。落地前務必先回答：誰能登入、如何認證、綁哪個網段、查詢是否留痕、能否大量匯出。
- **與 CLI 決策相容性**：**部分相容**。守住了「寫入走 CLI」，但違反了「不開對外管理端點」的字面與精神（唯讀版）。屬於對既有決策的**局部調整**，需擁有者明確簽核個資/攻擊面權衡。

### 方案 C：完整管理後台含 CRUD（把 CLI 操作也搬進 web）

web UI 涵蓋 B 的全部唯讀功能，另加租戶開通/停用、App 綁定、金鑰輪替、裝置撤銷等**寫入操作**。

- **優點**：單一入口、對非技術操作者最友善。
- **缺點**：**直接推翻 `CLAUDE.md` 已拍板的「CLI-only、不開對外管理端點」決策**——把特權寫入操作放上 web，正是當初評估後刻意否決的方案。當初否決的理由（雞生蛋的平台管理金鑰、擴大攻擊面、對唯一合法呼叫者無便利淨益、host-shell 已是隱含強認證）在此**全數重新成立且加倍**（寫入 + 個資查詢集於一身）。此外與容量假設不符：`CLAUDE.md` 明載這些是「一次性/低頻」操作、規模「中小（數萬會員、峰值 ≤100 TPS）」、租戶數量少——為極低頻的少量操作蓋一個高權限 web 系統，投報比與風險比都差。
- **實作成本量級**：**大**。前後端 + 認證授權 + 特權操作稽核 + 網段/加固 + 長期維運。
- **安全性/攻擊面**：**最高**。單點被攻陷即可跨租戶讀個資並執行平台級特權寫入。
- **與 CLI 決策相容性**：**衝突（reverse）**。這不是延伸或局部調整，而是覆蓋既有拍板決策。**依 systems-analyst 職責，此處明確標示為衝突、不默默採用**；若擁有者要走 C，等於重新開啟並推翻「租戶開通 / 簽章金鑰 CLI 決策」，需正式重議。

### 比較總表

| 方案 | 涵蓋需求 | 實作成本 | 個資/攻擊面 | 與 CLI 決策相容性 |
|---|---|---|---|---|
| **A 現狀+補齊工具** | 觀測(Grafana)＋唯讀查詢(CLI/SQL/BI)；不做裝置總覽 | **小** | **最低**（metrics 無 PII、查詢沿用既有 DB 權限） | **完全相容**（自然延伸） |
| **B 唯讀監控 UI** | 觀測＋唯讀查詢整合在 web | 中 | 中～高（新增觸及 `audit_log` 個資的認證端點） | 部分相容（守住寫入走 CLI，但違反「不開對外端點」精神，需簽核） |
| **C 完整 CRUD 後台** | 全部（含寫入） | **大** | **最高**（跨租戶讀個資＋平台特權寫入集於一身） | **衝突**：推翻既有拍板決策，需正式重議 |

---

## 3. 專業建議（建議，非決策）

**傾向建議：採方案 A，並把它拆成「先做、後議」兩段。**

1. **優先且低風險——開通可觀測性（現在就值得做）。** 這是三類需求中唯一「既有 CLI/SQL 都補不上、又不含個資、攻擊面最低」的真缺口。actuator 相依已在，只差曝露 `metrics`/`prometheus`（綁內網）+ 少量 Micrometer 業務指標 + 地端 Grafana 告警。rate limit 命中率、簽章失敗率、proximity mismatch 趨勢、自動撤銷異常——這些正是平台維運方最該持續盯著、目前卻完全沒有的訊號。投報比最高、與既有決策零衝突。

2. **唯讀業務查詢——先補一個 `list-tenants` CLI 指令止痛，其餘維持 DB/BI。** 「看清單只能查 DB」的痛點用一個唯讀 CLI 指令即可解，完全沿用已驗證的模式、零新增攻擊面。稽核/客服的複雜查詢先用唯讀 DB 帳號 + SQL/BI 工具承接。**先不要為此蓋 web UI。**

3. **把方案 B 定位為「觀察後再議」的觸發式選項，而非現在就做。** 只有當「非技術客服人員需要頻繁自助查稽核」成為實際、反覆出現的營運痛點，且 SQL/BI 確實無法勝任時，才升級到方案 B——**且升級前必須先請擁有者對個資法/攻擊面權衡簽核**，並把 UI 範圍嚴格限縮為唯讀、綁內網/VPN、強認證、對查詢本身留稽核、限制大量匯出。這與本專案處理其他敏感面向的一貫節奏一致（先設計、列出待簽核點、擁有者拍板後才動工）。

4. **不建議方案 C。** 它推翻既有已拍板決策、攻擊面最高，且與「一次性/低頻、中小規模」的容量假設（`CLAUDE.md`）明顯不成比例。除非擁有者有意重新開啟「CLI-only」決策，否則不應納入。

**這只是傾向。** 最終取捨取決於兩個只有擁有者能回答的問題：(1) 客服/稽核查詢的**實際頻率與操作者技術程度**——若營運上就是要讓非技術人員頻繁自助查詢，方案 B 的便利性可能值得那份個資/攻擊面成本；(2) 是否願意為此**局部鬆動「不開對外管理端點」**的既有決策。systems-analyst 不越權替您拍板；無論選 A 或 B，建議動工前先把「metrics 端點的網段限制」與（若選 B）「web UI 觸及 `audit_log` 個資的存取控制與稽核要求」這兩點結論寫下來，再交 dev-engineer / devops-engineer 承接。

---

## 附錄：查證依據

- 既有 CLI 決策與理由：`CLAUDE.md`「租戶開通 / 簽章金鑰 CLI 決策」段落；`fido-server/src/main/java/com/fido/server/admin/AdminCliRunner.java`、`application-admin-cli.yml`。
- actuator 現況：`fido-server/pom.xml`（含 `spring-boot-starter-actuator`）、`fido-server/src/main/resources/application.yml`（`management.endpoints.web.exposure.include: health,info`）。
- 稽核資料與個資欄位：`docs/db-schema.md` §8 `audit_log`（`tenant_id`/`user_ref_id`/`ip_address`/`detail`，1 年保留）。
- rate limit：`CLAUDE.md` 決策表「租戶速率限制」（每租戶 100 TPS / 429 / Retry-After）。
- 定位與容量假設：`CLAUDE.md` 決策表「FIDO 伺服器定位（僅後端驗證、非身分來源）」「容量目標（中小規模，數萬會員、峰值 ≤100 TPS）」「部署（全地端）」「法規範圍（台灣個資法）」。
- 裝置管理代理路徑：`shopping-site-reference` 的 `DeviceProxyController`（`/shop/api/fido/devices`）。
