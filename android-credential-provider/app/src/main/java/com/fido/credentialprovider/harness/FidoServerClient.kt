package com.fido.credentialprovider.harness

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 【PoC 專用測試 harness，非產品程式碼】直接呼叫 fido-server REST API
 * （`docs/api-contract.md`）。
 *
 * 真實產品中 Android APP 不會這麼做——它透過同裝置 Credential Manager 與購物網站前端互動，
 * 結果由購物網站後端轉呼叫 FIDO 伺服器（見 api-contract.md 前言）。本 harness 是 PoC 為了
 * 快速端對端驗證，模擬「購物網站前端 + 後端」的最小替代品（見
 * docs/android-poc-checklist.md 附錄 A-2，該做法已於任務中確認可行，但其「是否納入正式碼庫」
 * 仍待人工複核）。
 */
class FidoServerClient(private val baseUrl: String, private val apiKey: String) {

    data class HttpResult(val statusCode: Int, val body: JSONObject)

    fun registrationOptions(externalUserId: String, displayName: String?, deviceLabel: String?): HttpResult {
        val body = JSONObject().apply {
            put("externalUserId", externalUserId)
            if (displayName != null) put("displayName", displayName)
            if (deviceLabel != null) put("deviceLabel", deviceLabel)
        }
        return post("/api/v1/registration/options", body)
    }

    fun registrationResult(
        ceremonyId: String,
        externalUserId: String,
        credentialJson: JSONObject,
        deviceLabel: String?,
    ): HttpResult {
        val body = JSONObject().apply {
            put("ceremonyId", ceremonyId)
            put("externalUserId", externalUserId)
            put("credential", credentialJson)
            if (deviceLabel != null) put("deviceLabel", deviceLabel)
        }
        return post("/api/v1/registration/result", body)
    }

    fun authenticationOptions(externalUserId: String?): HttpResult {
        val body = JSONObject()
        if (externalUserId != null) body.put("externalUserId", externalUserId)
        return post("/api/v1/authentication/options", body)
    }

    fun authenticationResult(ceremonyId: String, credentialJson: JSONObject): HttpResult {
        val body = JSONObject().apply {
            put("ceremonyId", ceremonyId)
            put("credential", credentialJson)
        }
        return post("/api/v1/authentication/result", body)
    }

    fun listDevices(externalUserId: String, status: String = "ALL"): HttpResult {
        return get("/api/v1/users/$externalUserId/devices?status=$status")
    }

    fun fidoStatus(externalUserId: String): HttpResult {
        return get("/api/v1/users/$externalUserId/fido-status")
    }

    private fun post(path: String, body: JSONObject): HttpResult {
        val connection = openConnection(path, "POST")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        return readResponse(connection)
    }

    private fun get(path: String): HttpResult {
        val connection = openConnection(path, "GET")
        return readResponse(connection)
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-API-Key", apiKey)
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
