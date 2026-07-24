package com.fido.credentialprovider.crossdevice

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * App 直連 fido-server §3.4 端點 B（claim）/ C（result）/ E（deny）的 HTTPS client
 * （`docs/api-contract.md` §3.4、§1.2.2 / D16、§3.4.E / D18）。這是本 App 第一個**正式產品**的
 * 直連 fido-server 能力（見設計文件 4.3：現有 `CreatePasskeyActivity`/`GetPasskeyActivity` 完全不
 * 直連 fido-server，結果一律經瀏覽器/購物網站中繼）。
 *
 * **與既有 PoC harness `com.fido.credentialprovider.harness.FidoServerClient` 的關鍵差異**：
 * 那個 client 是**僅供 PoC 診斷**、模擬「購物網站前後端」直打全部端點、一律帶 `X-API-Key`
 * （見該檔案檔頭說明），只存在於 `poc` build flavor，不會出現在正式產物。這個 client 相反——
 * 它是**正式產品程式碼**（`prod`/`poc` 共用，放在 `src/main`），只呼叫 §3.4 這三個
 * **手機 App 直連**端點，且**刻意不帶 `X-API-Key`**（`xdevId` 本身即路徑上的 capability 認證，
 * 見 api-contract.md §1.2.2 / D16——手機是單一營運方 App、服務多租戶，掃 QR 當下無從得知
 * 該打哪個租戶的 API Key，也不應持有任何租戶的 API Key）。
 *
 * 沿用專案既有慣例（比照 harness 版 `FidoServerClient`）：標準 `HttpURLConnection` + `org.json`，
 * 不新增第三方 HTTP 依賴。
 */
class CrossDeviceServerClient(private val baseUrl: String) {

    data class HttpResult(val statusCode: Int, val body: JSONObject)

    /**
     * `POST .../deny`（api-contract.md §3.4.E）request body 的 `reason` 列舉值。伺服器**僅接受**
     * 這兩個值（§3.4.E Request 表：「僅供稽核分類，不影響狀態轉移結果」），省略時伺服器自行記為
     * `UNSPECIFIED`——本 client 不提供「不帶 reason」的呼叫方式，因為 App 端呼叫 deny 的唯二時機
     * （使用者取消 / 本機無憑證）永遠明確知道是哪一種，見 [CrossDeviceLoginFlow.denyReasonFor]。
     */
    enum class DenyReason(val wireValue: String) {
        USER_CANCELLED("USER_CANCELLED"),
        NO_CREDENTIAL("NO_CREDENTIAL"),
    }

    /**
     * `POST /api/v1/authentication/cross-device/sessions/{xdevId}/claim`（手機 App 直連，
     * `xdevId` capability，不帶 X-API-Key）。api-contract.md §3.4.B 未列出額外 request 欄位，
     * `xdevId` 本身已在路徑上，故 body 送空 JSON 物件。
     */
    fun claim(xdevId: String): HttpResult {
        return post("/api/v1/authentication/cross-device/sessions/$xdevId/claim", JSONObject())
    }

    /**
     * `POST /api/v1/authentication/cross-device/sessions/{xdevId}/result`（手機 App 直連，
     * `xdevId` capability，不帶 X-API-Key）。body 為標準 assertion JSON
     * （`id`/`rawId`/`type`/`response.{clientDataJSON,authenticatorData,signature}`，見
     * api-contract.md §3.4.C：與 §3.2 `credential.{...}` 同一結構，此處**不**額外包一層
     * `credential`/`ceremonyId` 欄位——ceremony 由伺服器以 `xdevId` 反查，不需要呼叫端另外指定）。
     */
    fun submitResult(xdevId: String, assertionJson: JSONObject): HttpResult {
        return post("/api/v1/authentication/cross-device/sessions/$xdevId/result", assertionJson)
    }

    /**
     * `POST /api/v1/authentication/cross-device/sessions/{xdevId}/deny`（手機 App 直連，
     * `xdevId` capability，不帶 X-API-Key，見 api-contract.md §3.4.E / D18）。呼叫時機：
     * 使用者在確認畫面按「不是我，取消」（[DenyReason.USER_CANCELLED]）或 claim 後發現本機無該
     * rpId 的 active 憑證（[DenyReason.NO_CREDENTIAL]）。呼叫端（[CrossDeviceLoginActivity]）
     * 一律以 best-effort 方式呼叫本方法（見 [CrossDeviceDenyReporter]）——這兩種情境的本機使用者
     * 體驗（取消/無憑證）已經確定，deny 呼叫失敗頂多是稽核訊號沒送達，不應該讓呼叫端因此卡住或
     * 顯示錯誤畫面。
     */
    fun deny(xdevId: String, reason: DenyReason): HttpResult {
        val body = JSONObject().put("reason", reason.wireValue)
        return post("/api/v1/authentication/cross-device/sessions/$xdevId/deny", body)
    }

    private fun post(path: String, body: JSONObject): HttpResult {
        val connection = openConnection(path)
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        return readResponse(connection)
    }

    private fun openConnection(path: String): HttpURLConnection {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        // 【刻意不設定 X-API-Key】見本類別檔頭說明與 api-contract.md §1.2.2 / D16——情境三端點
        // B/C 以 xdevId capability 認證，不帶 API Key（與 harness 版 FidoServerClient 的其他
        // 端點呼叫方式刻意不同，不是遺漏）。
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): HttpResult {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: "{}"
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("_rawBody", text)
        }
        return HttpResult(statusCode, json)
    }
}
