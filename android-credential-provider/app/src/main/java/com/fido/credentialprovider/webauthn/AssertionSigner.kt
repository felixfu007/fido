package com.fido.credentialprovider.webauthn

import android.content.Context
import android.util.Base64
import com.fido.credentialprovider.keystore.LocalCredentialStore
import org.json.JSONObject
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

/**
 * WebAuthn assertion 簽章核心，從 [com.fido.credentialprovider.ui.GetPasskeyActivity]
 * （原 `performAssertion` 私有方法）抽出，供
 * [com.fido.credentialprovider.ui.CrossDeviceLoginActivity]（情境三跨裝置 QR 登入，新入口）共用。
 * 對應設計文件 `docs/decisions/qr-cross-device-login-design.md` 4.3「直接重用
 * `GetPasskeyActivity.performAssertion` 的簽章核心」。
 *
 * **重構原則（務必遵守）**：純粹搬移既有邏輯到獨立、框架相依但與 Activity 生命週期無關的物件，
 * **不改變任何行為**——`GetPasskeyActivity` 改為委派呼叫本物件，輸出的 assertion JSON 結構、
 * 簽章演算法（`SHA256withECDSA`、對 `authenticatorData || clientDataHash` 簽章）、
 * sign counter 遞增時機（簽章成功後才 `setSignCount`）皆與搬移前逐字相同。
 * [com.fido.credentialprovider.ui.CreatePasskeyActivity] 的註冊流程不受影響（未觸碰該檔案）。
 *
 * 呼叫端須在非主執行緒呼叫（Keystore 簽章為阻塞操作，沿用既有 `Thread { }` 慣例）。
 */
object AssertionSigner {

    /** 對應原 `performAssertion` 內「Keystore 找不到別名」的早退分支，供呼叫端保留原本訊息文案。 */
    class KeyNotFoundException(message: String) : Exception(message)

    data class Signed(
        val credentialIdB64Url: String,
        val newSignCount: Long,
        val assertionResponseJson: String,
    )

    /**
     * 對指定的本機 credential 產生一次完整 WebAuthn assertion：authenticatorData 組裝
     * （[AuthenticatorDataBuilder.buildForAssertion]）、clientDataJSON/hash 決議
     * （[ClientDataBuilder.build] + [ClientDataBuilder.resolveClientDataHash]）、
     * `SHA256withECDSA` 簽章、sign counter 遞增（[LocalCredentialStore.setSignCount]）、
     * 組出回應 JSON（[buildAssertionResponseJson]）。
     *
     * @param callerSuppliedClientDataHash 見 [ClientDataBuilder.resolveClientDataHash]——同裝置
     *   情境（瀏覽器呼叫端）可能提供；跨裝置情境（本 App 自己是唯一 client，沒有瀏覽器）一律傳
     *   null，走自建 clientDataJSON 重新雜湊的路徑（見設計文件 4.2 步驟 6）。
     */
    fun sign(
        context: Context,
        credentialIdB64Url: String,
        rpId: String,
        challengeB64Url: String,
        origin: String,
        callerSuppliedClientDataHash: ByteArray?,
    ): Signed {
        val alias = LocalCredentialStore.aliasFor(credentialIdB64Url)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            throw KeyNotFoundException("Key not found for this credential")
        }
        val privateKey = keyStore.getKey(alias, null) as PrivateKey

        // sign counter：本機維護，見 LocalCredentialStore 說明；每次成功簽章遞增 1。
        val newCount = LocalCredentialStore.getSignCount(context, credentialIdB64Url) + 1

        val authenticatorData = AuthenticatorDataBuilder.buildForAssertion(
            rpId = rpId,
            userVerified = true,
            signCount = newCount,
        )
        val clientDataJson = ClientDataBuilder.build(
            type = "webauthn.get",
            challengeBase64Url = challengeB64Url,
            origin = origin,
        )
        val clientDataHash = ClientDataBuilder.resolveClientDataHash(
            callerSuppliedClientDataHash,
            clientDataJson,
        )

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(authenticatorData)
            update(clientDataHash)
        }.sign()

        LocalCredentialStore.setSignCount(context, credentialIdB64Url, newCount)

        val assertionResponseJson = buildAssertionResponseJson(
            credentialIdB64Url = credentialIdB64Url,
            clientDataJson = clientDataJson,
            authenticatorData = authenticatorData,
            signature = signature,
        )

        return Signed(
            credentialIdB64Url = credentialIdB64Url,
            newSignCount = newCount,
            assertionResponseJson = assertionResponseJson,
        )
    }

    fun buildAssertionResponseJson(
        credentialIdB64Url: String,
        clientDataJson: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
    ): String {
        val response = JSONObject()
            .put("clientDataJSON", b64UrlEncode(clientDataJson))
            .put("authenticatorData", b64UrlEncode(authenticatorData))
            .put("signature", b64UrlEncode(signature))

        return JSONObject()
            .put("id", credentialIdB64Url)
            .put("rawId", credentialIdB64Url)
            .put("type", "public-key")
            .put("clientExtensionResults", JSONObject())
            .put("response", response)
            .toString()
    }

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
