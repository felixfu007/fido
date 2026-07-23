package com.fido.testcaller

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

/**
 * 【驗證專用、非產品程式碼】見 build.gradle.kts 檔頭說明。這個 Activity 就是「假裝自己是
 * 購物網站原生 App」的呼叫方：不指定任何 origin，直接用自己的 applicationContext 呼叫
 * CredentialManager.createCredential()，逼系統走 androidx.credentials 的原生 App 路徑
 * （非瀏覽器代理），讓 fido-credential-provider 的 OriginResolver 從本 App 的
 * [android.content.pm.SigningInfo] 推導 `android:apk-key-hash:...`。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FidoOriginTestCaller"
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            text = "尚未觸發 createCredential()"
            textSize = 16f
            setPadding(32, 64, 32, 32)
        }
        val button = Button(this).apply {
            text = "觸發原生 App createCredential()"
            setOnClickListener { triggerCreateCredential() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(button)
        }
        setContentView(root)
    }

    private fun triggerCreateCredential() {
        Log.i(TAG, "觸發 createCredential()：applicationId=$packageName（無指定 origin，走原生 App 路徑）")
        statusView.text = "呼叫中…請於系統 bottom sheet 選擇 FIDO Credential Provider"

        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val challengeB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
        val userId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })

        // 對應 CreatePasskeyActivity.parseCreationOptions() 只讀 rp.id / challenge，其餘欄位
        // 純粹是為了讓 CreatePublicKeyCredentialRequest 的 client 端 JSON 格式驗證通過
        // （標準 WebAuthn PublicKeyCredentialCreationOptions 形狀）。rpId 本身與本次驗證目的
        // （origin 解析路徑）無關，任意合法網域字串即可。
        val requestJson = """
            {
              "rp": { "id": "origin-verify.example.com", "name": "Origin Verify Test RP" },
              "user": { "id": "$userId", "name": "origin-verify-user", "displayName": "Origin Verify User" },
              "challenge": "$challengeB64Url",
              "pubKeyCredParams": [ { "type": "public-key", "alg": -7 } ],
              "timeout": 60000,
              "attestation": "direct"
            }
        """.trimIndent()

        val request = CreatePublicKeyCredentialRequest(requestJson = requestJson)
        val credentialManager = CredentialManager.create(applicationContext)

        lifecycleScope.launch {
            try {
                val result = credentialManager.createCredential(
                    context = this@MainActivity,
                    request = request,
                )
                onSuccess(result)
            } catch (e: CreateCredentialException) {
                onFailure(e)
            }
        }
    }

    private fun onSuccess(response: CreateCredentialResponse) {
        Log.i(TAG, "createCredential() 成功：type=${response.type}")
        statusView.text = "成功：${response.type}（請查看 provider 端 logcat 的 origin 解析紀錄）"
    }

    private fun onFailure(e: CreateCredentialException) {
        // 對本次驗證目的（origin 是否正確解析）而言，即使後續因模擬器無 StrongBox/TEE 硬體
        // 導致金鑰產生被拒絕（HARDWARE_SECURITY_NOT_MET）而整體流程以例外收場，也不影響
        // 驗證結果——origin 解析與其 log 發生在金鑰產生「之前」（見
        // CreatePasskeyActivity.onCreate 的程式碼順序與任務回報說明）。
        Log.w(TAG, "createCredential() 未成功（可能純粹是模擬器無硬體安全模組，不影響 origin 解析驗證）：" +
            "${e.type} ${e.message}", e)
        statusView.text = "未成功：${e.type} ${e.message}\n（若原因與硬體金鑰無關，請查看 provider 端 logcat 的 origin 解析紀錄）"
    }
}
