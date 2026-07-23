package com.fido.credentialprovider

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.fido.credentialprovider.keystore.LocalCredentialStore
import com.fido.credentialprovider.ui.CreatePasskeyActivity
import com.fido.credentialprovider.ui.GetPasskeyActivity

/**
 * 【清單項目 1 核心】自訂 CredentialProviderService，掛載到 Android 14 系統 Credential
 * Manager（對齊 CLAUDE.md「自訂 Android CredentialProviderService，掛載於系統 Credential
 * Manager（非獨立 APP 跳轉、非推播）」）。
 *
 * 本 service 只負責「列出 entry」；使用者在系統 Credential Manager bottom sheet 選定 entry
 * 後，系統才會透過 entry 附帶的 PendingIntent 啟動 [CreatePasskeyActivity] /
 * [GetPasskeyActivity] 執行實際的 Keystore 金鑰操作（這是 androidx.credentials.provider API
 * 的標準模式，並非「跳轉到獨立 APP」——bottom sheet 本身仍是系統 UI，見
 * AndroidManifest.xml 內兩個 Activity 使用 Dialog 主題的說明）。
 */
class FidoCredentialProviderService : CredentialProviderService() {

    companion object {
        private const val TAG = "FidoCredentialProviderService"
        private const val REQUEST_CODE_CREATE = 1001
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        Log.i(TAG, "onBeginCreateCredentialRequest 被系統呼叫：requestType=${request.javaClass.simpleName}")
        when (request) {
            is BeginCreatePublicKeyCredentialRequest -> {
                try {
                    callback.onResult(buildCreateResponse())
                } catch (e: Exception) {
                    Log.e(TAG, "建立 CreateEntry 失敗：${e.message}", e)
                    callback.onError(CreateCredentialUnknownException(e.message))
                }
            }
            else -> {
                Log.w(TAG, "不支援的 create 請求型別：${request.javaClass.name}")
                callback.onError(CreateCredentialUnknownException("Unsupported create request type"))
            }
        }
    }

    private fun buildCreateResponse(): BeginCreateCredentialResponse {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_CREATE,
            Intent(applicationContext, CreatePasskeyActivity::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val createEntry = CreateEntry.Builder(
            getString(R.string.create_entry_label),
            pendingIntent,
        )
            .setDescription(getString(R.string.create_entry_description))
            .build()

        return BeginCreateCredentialResponse.Builder()
            .setCreateEntries(listOf(createEntry))
            .build()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        Log.i(TAG, "onBeginGetCredentialRequest 被系統呼叫：optionCount=${request.beginGetCredentialOptions.size}")
        try {
            val entries = mutableListOf<PublicKeyCredentialEntry>()
            for (option in request.beginGetCredentialOptions) {
                if (option is BeginGetPublicKeyCredentialOption) {
                    entries += buildEntriesForOption(option)
                }
            }
            Log.i(TAG, "本次 getCredential 提供 ${entries.size} 個本機 passkey entry")
            val response = BeginGetCredentialResponse.Builder()
                .setCredentialEntries(entries)
                .build()
            callback.onResult(response)
        } catch (e: Exception) {
            Log.e(TAG, "建立 CredentialEntry 失敗：${e.message}", e)
            callback.onError(GetCredentialUnknownException(e.message))
        }
    }

    private fun buildEntriesForOption(option: BeginGetPublicKeyCredentialOption): List<PublicKeyCredentialEntry> {
        // 【2026-07-23 真機登入驗證修正】原本無條件套用 PocConfig.RP_ID（"shop.example.com"）
        // 過濾本機已註冊 credential，導致以其他 rpId 註冊的裝置（例如本機 Chrome 真機測試環境
        // 用 rpId=localhost，因為手機連不到 shop.example.com，見 CLAUDE.md 任務環境說明）在
        // 登入時被此 provider 判定「查無本機 passkey entry」而完全不出現在系統選單——不是
        // clientDataHash 那個根因造成的問題，是本檔案自己這處過濾條件寫死的既有已知缺口
        // （見本函式原本的註解），這裡把它按該註解已指出的方向修正：優先採用
        // option.requestJson 內實際的 rpId（PublicKeyCredentialRequestOptions 頂層 "rpId"
        // 欄位，對齊 fido-server AuthenticationOptionsResponse.AssertionOptions.rpId），解析
        // 失敗或缺漏才 fallback 到 PocConfig.RP_ID，維持 PoC 單租戶情境下的原本行為不變。
        val rpId = resolveRpIdFromRequestJson(option.requestJson) ?: PocConfig.RP_ID
        val credentialIds = LocalCredentialStore.listCredentialIds(applicationContext, rpId)
        return credentialIds.map { credentialId ->
            val intent = Intent(applicationContext, GetPasskeyActivity::class.java).apply {
                data = Uri.parse("fido-provider://credential/$credentialId")
                putExtra(GetPasskeyActivity.EXTRA_CREDENTIAL_ID, credentialId)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                credentialId.hashCode(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            PublicKeyCredentialEntry.Builder(
                applicationContext,
                shortLabel(credentialId),
                pendingIntent,
                option,
            ).build()
        }
    }

    private fun resolveRpIdFromRequestJson(requestJson: String): String? = try {
        org.json.JSONObject(requestJson).optString("rpId").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "getOptions requestJson 解析 rpId 失敗，改用 PocConfig.RP_ID：${e.message}")
        null
    }

    private fun shortLabel(credentialId: String): String {
        val prefix = credentialId.take(8)
        return "FIDO device ($prefix…)"
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, androidx.credentials.exceptions.ClearCredentialException>,
    ) {
        // 本 PoC 不維護跨 App session 狀態，無需清除任何東西。
        callback.onResult(null)
    }
}
