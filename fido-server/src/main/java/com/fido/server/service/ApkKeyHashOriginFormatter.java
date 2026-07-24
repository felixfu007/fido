package com.fido.server.service;

import java.util.Base64;

/**
 * 把 Android App 簽章憑證的 SHA-256 指紋位元組，換算成 WebAuthn app origin 字串
 * {@code android:apk-key-hash:<base64url(fingerprint)>}（db-schema.md 第 9 節
 * {@code tenant_app_bindings.apk_key_hash_origin} / docs/origin-binding.md）。
 *
 * <p>格式對齊 {@code android-credential-provider} 端
 * {@code OriginResolver.apkKeyHashOrigin(certificateDer: ByteArray)} 的
 * {@code "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)}
 * 邏輯（Kotlin 那份是「憑證 DER -&gt; SHA-256 -&gt; base64url」，本類別的輸入已經是算好的
 * SHA-256 指紋位元組，故只做「指紋 -&gt; base64url -&gt; 加前綴」這一段，兩邊編碼規則完全一致）。
 */
public final class ApkKeyHashOriginFormatter {

    private static final String PREFIX = "android:apk-key-hash:";

    private ApkKeyHashOriginFormatter() {
    }

    public static String format(byte[] sha256Fingerprint) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Fingerprint);
        return PREFIX + encoded;
    }
}
