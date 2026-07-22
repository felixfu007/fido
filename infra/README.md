# FIDO 伺服器資料庫 — 建置與維運腳本

本目錄由 devops-engineer 依 `d:\fido\CLAUDE.md`（架構決策共識）與
`d:\fido\docs\db-schema.md`（六張核心表權威 schema）建立，內容為**可執行的
T-SQL 腳本**，供之後在真正的 SQL Server 執行個體上部署使用。

> **重要聲明（誠實揭露驗證侷限）**：撰寫本目錄腳本的這台開發機**沒有可連線的
> SQL Server 執行個體**（僅有 `SQLWriter` 服務在跑，無 `sqlcmd`、無 Docker），
> 因此下列腳本**未經端對端實際執行驗證**，只做了以下程度的檢查：
> - 逐行比對 `docs/db-schema.md` 章節 3-9 的 DDL 與索引/外鍵總表，確認欄位、
>   型別、PK/UNIQUE/CHECK/FK、索引定義彼此一致（詳見下方「與 db-schema.md 的
>   比對結果」）。
> - 依語法規則與過往對 T-SQL / SQL Server Agent 系統預存程序（`sp_add_job` /
>   `sp_add_jobstep` / `sp_add_schedule` / `sp_attach_schedule` / `sp_add_jobserver`）
>   簽章的理解手動覆核語法，並非透過實際執行編譯器/剖析器驗證。
> - **未**在任何 SQL Server 執行個體上實際執行過這些腳本，因此無法保證 100%
>   零語法錯誤、零遺漏權限、零路徑問題（例如腳本中的磁碟路徑 `D:\SQLData\...`、
>   `E:\SQLLog\...`、`D:\SQLBackup\...`、`D:\SQLSecure\TDE\...` 皆為範例，實際
>   部署機器的磁碟代號與資料夾需相應調整並事先建立）。
> - 正式部署前，**務必先在非正式（測試/staging）SQL Server 執行個體上完整跑過
>   一次本目錄全部腳本**，並依下方「部署後驗證清單」逐項確認，再上正式環境。

---

## 檔案清單與用途

| 檔案 | 用途 |
|---|---|
| `sql/001_create_database.sql` | 建立 `FidoServerDb` 資料庫（資料檔/記錄檔路徑、初始大小、成長設定）、設定 RECOVERY FULL（支援記錄備份）、相容性層級、`READ_COMMITTED_SNAPSHOT` 等資料庫層級設定 |
| `sql/002_create_tables.sql` | 建立六張核心表：`tenants`、`fido_user_ref`、`fido_credentials`、`bound_devices`、`auth_challenges`、`audit_log`，含 PK/UNIQUE/CHECK/FK（依 `docs/db-schema.md` 第 3-8 節 DDL） |
| `sql/003_create_indexes.sql` | 建立各表次要（非唯一）索引（依 `docs/db-schema.md` 第 9 節索引總表） |
| `sql/004_enable_tde.sql` | 啟用 TDE 全庫加密：Database Master Key → 憑證 → Database Encryption Key → `ALTER DATABASE ... SET ENCRYPTION ON`，並**立即備份憑證與私密金鑰**（含備份後的保管注意事項） |
| `sql/005_scheduled_backups.sql` | 建立 SQL Server Agent Job：完整備份（週日 01:00）、差異備份（每日 02:00）、交易記錄備份（每 30 分鐘） |
| `sql/006_retention_cleanup_jobs.sql` | 建立 SQL Server Agent Job：`auth_challenges` 過期標記（每分鐘）+ 每日清除超過 1 天的舊列、`audit_log` 每日清除超過 365 天的舊列（對齊稽核保留 1 年） |
| `README.md`（本檔） | 部署順序、前置需求、驗證清單、已知限制、待人工拍板事項 |

---

## 執行順序

依檔名編號**依序**在**目標 SQL Server 執行個體**上執行（建議用 `sqlcmd` 或 SSMS，
逐檔執行並確認每檔皆印出 `... 執行完成` 訊息、無紅字錯誤後才進行下一檔）：

```
001_create_database.sql
002_create_tables.sql
003_create_indexes.sql
004_enable_tde.sql
005_scheduled_backups.sql
006_retention_cleanup_jobs.sql
```

`004` 執行完成後，**在讓 `005` 的排程接手前，建議立即手動觸發一次
`FidoServerDb - Full Backup` Job（或手動執行一次 `BACKUP DATABASE`）**，
理由見 `005_scheduled_backups.sql` 檔頭註解（FULL 復原模式下，第一次記錄備份
必須晚於第一次完整備份，否則會失敗）。

---

## 前置需求

- **SQL Server 版本**：建議 SQL Server 2019 Standard Edition 以上（TDE 自
  2019 起 Standard Edition 即支援；2019 之前版本 TDE 僅 Enterprise 支援，若
  目標環境版本較舊，請先跟 systems-analyst / 採購確認授權版本，否則 `004` 會
  失敗）。若確定使用的版本不同，`001_create_database.sql` 中的
  `COMPATIBILITY_LEVEL` 請對應調整。
- **獨立執行個體**：依 CLAUDE.md 決策，本資料庫應部署在與其他系統分開的**獨立
  SQL Server 執行個體**，而非共用實例上的另一個資料庫。
- **作業系統/磁碟**：全地端部署（Windows Server 主機）；建議資料檔、記錄檔、
  備份檔、TDE 憑證備份分別存放於不同實體磁碟/儲存裝置。腳本內路徑皆為範例，
  部署前需依實際磁碟配置調整，並**事先手動建立對應資料夾**（`CREATE DATABASE`
  與 `BACKUP` 不會自動建立資料夾）。
- **權限**：建置階段（`001`-`006`）建議以 `sysadmin` 執行，因涉及建立資料庫、
  Master Key、憑證、啟用 TDE、建立 SQL Agent Job 等高權限操作。正式營運期間
  應用程式連線帳號應改用最小權限（僅六張表的 `SELECT/INSERT/UPDATE`，不需
  `sysadmin`），惟建立該應用程式連線帳號屬於資料庫存取設定，不在本次任務範圍
  （本次任務不涉及應用程式連線設定，僅建庫與維運腳本）。
- **SQL Server Agent 服務**：必須為「執行中」且設為自動啟動，否則 `005`、
  `006` 建立的所有排程 Job 都不會被觸發。
- **Job 擁有者登入帳號**：`005`、`006` 內的 `@owner_login_name` 預設為 `N'sa'`。
  若目標環境已停用或重新命名 `sa`（常見資安強化作法），執行前請先將腳本內所有
  `@owner_login_name = N'sa'` 改成實際存在且具 `sysadmin` 權限的登入帳號。
- **密碼與金鑰保管**：`004_enable_tde.sql` 內的
  `REPLACE_WITH_STRONG_MASTER_KEY_PASSWORD_!23`、
  `REPLACE_WITH_STRONG_PVK_PASSWORD_!45` 為佔位符，**正式執行前必須替換成正式
  環境專用強密碼**，且不應與本腳本檔案一同留存於版控（建議執行時另行輸入或改
  以參數化/密碼管理工具帶入，執行完後腳本中的明文密碼可考慮從實際執行紀錄中
  清除）。

---

## 部署後驗證清單

給實際執行部署的人（可能是你自己在測試環境跑一次，或未來正式環境的操作者）核對：

**資料庫與資料表**
- [ ] `SELECT name, recovery_model_desc FROM sys.databases WHERE name = 'FidoServerDb';` 回傳 `recovery_model_desc = 'FULL'`
- [ ] `SELECT COUNT(*) FROM sys.tables WHERE schema_id = SCHEMA_ID('dbo');`（在 `FidoServerDb` 內執行）回傳 6
- [ ] 六張表名稱與 `docs/db-schema.md` 第 2 節 ER 關係一致：`tenants`、`fido_user_ref`、`fido_credentials`、`bound_devices`、`auth_challenges`、`audit_log`
- [ ] `SELECT COUNT(*) FROM sys.foreign_keys;`（在 `FidoServerDb` 內執行）回傳 10：`fido_user_ref`→tenants 1 個、`fido_credentials`→user_ref+tenants 2 個、`bound_devices`→credential+user_ref+tenants 3 個、`auth_challenges`→tenants+user_ref 2 個、`audit_log`→tenants+user_ref 2 個（合計 1+2+3+2+2=10）。請仍以實際查詢結果為準並與本文件逐一核對，不要只憑此數字判斷正確
- [ ] 逐表核對 `sys.indexes` 的索引數量與名稱是否對齊 `docs/db-schema.md` 第 9 節總表

**TDE**
- [ ] 執行 `004_enable_tde.sql` 最後的驗證查詢，確認 `encryption_state = 3`（Encrypted）且 `percent_complete = 0`
- [ ] 確認 `TDE_FidoServerDb_Cert.cer` 與 `TDE_FidoServerDb_Cert.pvk` 兩個檔案，已從資料庫伺服器複製到**另一個獨立、安全的儲存位置**（不是只留在原機器）
- [ ] 確認私密金鑰加密密碼、Master Key 密碼已存放於密碼管理工具/保險箱，且未與憑證檔案放在同一位置
- [ ] **強烈建議**：在一台測試用 SQL Server 執行個體上，實際演練一次「還原憑證
      （`CREATE CERTIFICATE ... FROM FILE ... WITH PRIVATE KEY ...`）+ 還原本資料庫
      備份」的完整流程，確認備份組合真的可用。這一步在本次任務中**未執行**（沒有
      可用的 SQL Server 環境），是部署前務必補做的驗證。

**備份排程**
- [ ] `SELECT * FROM msdb.dbo.sysjobs WHERE name LIKE N'FidoServerDb%';` 可看到 6 個 Job（3 個備份 + 3 個清理）且 `enabled = 1`
- [ ] SQL Server Agent 服務狀態為「執行中」
- [ ] 已手動觸發一次 `FidoServerDb - Full Backup` 並確認成功（`msdb.dbo.sysjobhistory` 查得到成功紀錄，且備份檔案確實出現在目標路徑）
- [ ] 等待排程觸發後，確認差異備份、記錄備份皆能正常產生檔案且無錯誤
- [ ] 演練一次「完整備份 + 差異備份 + 記錄備份」的還原鏈（`RESTORE DATABASE ... WITH NORECOVERY` 依序套用），確認可還原到預期時間點

**資料保留清理**
- [ ] 等待數分鐘後確認 `FidoServerDb - Expire Auth Challenges` Job 有執行紀錄且成功
- [ ] 以測試資料驗證：插入一筆 `created_at` 為 2 天前的 `auth_challenges` 測試列，隔日（或手動執行該 Job）確認已被清除
- [ ] 以測試資料驗證：插入一筆 `created_at` 為 366 天前的 `audit_log` 測試列，執行 `FidoServerDb - AuditLog Retention Cleanup` Job 後確認已被清除，且 364 天前的列仍保留（確認邊界值正確、未誤刪未滿 1 年的稽核資料）

---

## 與 `docs/db-schema.md` 比對結果

逐表比對章節 3-8 的 DDL 與章節 9「索引與外鍵總表」，**欄位、型別、PK/UNIQUE/CHECK
/FK、索引定義三者互相一致，未發現需要訂正之處**；`sql/002_create_tables.sql` 與
`sql/003_create_indexes.sql` 為逐字採用該文件 DDL（僅新增 `IF OBJECT_ID(...) IS
NULL` / `IF NOT EXISTS (...)` 的冪等性防呆包裝，未變更任何欄位、型別或約束邏輯）。

`docs/db-schema.md` 文末「附錄 B」列出 DB2、DB14、DB15 為「待複核項」——這些是
**schema 設計取捨**（API Key 雜湊儲存方式、憑證與裝置 1:1 關係、單一 schema 邏輯
隔離），不影響本次 DDL 語法本身是否正確可執行，故本次仍依文件既有定案原樣建置；
若之後這三項複核有變動，屬於 schema 變更，需回頭修改 `docs/db-schema.md` 與
`sql/002_create_tables.sql`／`003_create_indexes.sql`，非本次任務範圍內可自行認定。

第 10 節「資料保留與清理排程」對 `audit_log` 清理提出「每日 DELETE」與「按月分割
+ SWITCH/DROP」兩個選項並列為建議、非定案 DDL。本次在 `sql/006_retention_cleanup_
jobs.sql` 中選擇**每日批次 DELETE** 方案，理由與取捨說明見該檔案檔頭註解（對齊
CLAUDE.md 中小規模容量目標，避免為此規模引入分割表的維運複雜度）。此為本次任務
指示「不用太複雜」下的工程判斷，非偏離 CLAUDE.md 容量目標或部署決策，故未另外
升級請示；如果之後認為應改採分割表方案，建議先與 systems-analyst 確認。

---

## 待人工拍板的開放問題

1. **資料庫/檔案的實際磁碟路徑與命名**：本次腳本中的 `D:\SQLData\...`、
   `E:\SQLLog\...`、`D:\SQLBackup\...`、`D:\SQLSecure\TDE\...` 皆為範例值，需要
   實際目標主機的磁碟配置資訊才能定案（哪個磁碟代號、多大容量、是否有專用的
   異地備份儲存）。
2. **TDE 憑證備份的異地保管方式**：腳本已包含「立即備份憑證+私密金鑰」的步驟，
   但「備份檔案要放去哪個異地安全位置、由誰保管密碼」屬於公司內部金鑰管理政策，
   需要人工決定並執行（腳本無法代勞這件事）。
3. **備份保留天數與舊備份檔清理機制**：`005_scheduled_backups.sql` 檔尾僅列出
   建議天數與清理方式的說明，未包含可直接執行的清理腳本（因 `xp_delete_file`
   屬未公開文件化的擴充預存程序，未在此環境驗證過，不願意在沒把握的情況下
   生出可能跑不動或行為不可預期的腳本）。建議之後在測試環境確認可用性後補上，
   或改用 Windows 工作排程器 + PowerShell 腳本處理備份檔案生命週期。
4. **應用程式資料庫連線帳號與最小權限設定**：本次任務刻意不涉及（屬於連接
   `fido-server` 應用程式的資料庫存取設定，非純建庫範疇）；待 dev-engineer
   要把記憶體內 repository 換成真正的 JPA/JDBC 實作時，需要另外建立一個最小
   權限的 SQL Server 登入帳號（僅六張表 CRUD 權限，不應是 `sysadmin` 或
   `db_owner`），這部分建議屆時由 devops-engineer 與 dev-engineer 一起確認
   連線方式（含憑證/密碼管理）。
5. **SQL Server 版本與授權**：腳本假設 SQL Server 2019 Standard 以上，如目標
   環境版本或授權級別不同，需要先確認再套用（尤其會影響 TDE 是否可用）。

---

## 明確不在本次範圍內

依任務邊界，本次**未**修改 `fido-server/` 下任何應用程式碼（例如未新增 JPA
entity、未變更 `pom.xml` 加入資料庫 driver）。目前 `fido-server` 仍使用記憶體內
repository；將其換成連接本目錄建置的 SQL Server 資料庫，屬於 dev-engineer 的
工作範圍。
