package com.fido.credentialprovider.keystore

import android.content.Context
import android.util.Base64

/**
 * 【PoC 簡化實作】本機 credentialId -> Keystore alias / rpId / sign counter 對應表。
 *
 * WebAuthn sign counter 由「authenticator」自行維護與遞增（本 provider 即是 authenticator），
 * Android Keystore 本身不提供這個概念，故需要 provider 自己記錄。正式產品應評估更穩固的儲存
 * 方式（例如搭配 Keystore 的 attestation 或加密後的本機資料庫），本 PoC 用 SharedPreferences
 * 已足夠驗證清單項目 6/7 的邏輯正確性。
 *
 * alias 命名規則：`fido_<credentialId base64url>`，讓 [com.fido.credentialprovider.ui.GetPasskeyActivity]
 * 可以直接從 WebAuthn credential id 反推 Keystore alias，不需要額外查表。
 */
object LocalCredentialStore {

    private const val PREFS_NAME = "fido_credential_store"
    private const val KEY_CREDENTIAL_IDS = "credential_ids"

    fun aliasFor(credentialIdBase64Url: String): String = "fido_$credentialIdBase64Url"

    fun rememberCredential(context: Context, credentialIdBase64Url: String, rpId: String) {
        val prefs = prefs(context)
        val ids = (prefs.getStringSet(KEY_CREDENTIAL_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(credentialIdBase64Url)
        prefs.edit()
            .putStringSet(KEY_CREDENTIAL_IDS, ids)
            .putString(rpIdKey(credentialIdBase64Url), rpId)
            .putLong(signCountKey(credentialIdBase64Url), 0L)
            .apply()
    }

    fun listCredentialIds(context: Context, rpId: String? = null): List<String> {
        val prefs = prefs(context)
        val ids = prefs.getStringSet(KEY_CREDENTIAL_IDS, emptySet()) ?: emptySet()
        return ids.filter { rpId == null || prefs.getString(rpIdKey(it), null) == rpId }
    }

    fun getRpId(context: Context, credentialIdBase64Url: String): String? =
        prefs(context).getString(rpIdKey(credentialIdBase64Url), null)

    fun getSignCount(context: Context, credentialIdBase64Url: String): Long =
        prefs(context).getLong(signCountKey(credentialIdBase64Url), 0L)

    fun setSignCount(context: Context, credentialIdBase64Url: String, value: Long) {
        prefs(context).edit().putLong(signCountKey(credentialIdBase64Url), value).apply()
    }

    /** 【測試用】強制把 sign counter 重設為 0，供 harness 模擬「counter 倒退」情境（清單項目 6 反向測試）。 */
    fun forceResetSignCount(context: Context, credentialIdBase64Url: String) {
        setSignCount(context, credentialIdBase64Url, 0L)
    }

    fun randomCredentialIdBase64Url(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun rpIdKey(id: String) = "rpid_$id"
    private fun signCountKey(id: String) = "signcount_$id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
