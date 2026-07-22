package com.fido.credentialprovider.webauthn

import java.security.cert.X509Certificate

/**
 * 【PoC 最高風險項目：清單項目 3】手動組出 WebAuthn {@code fmt="android-key"} 的
 * attestationObject，對齊 fido-server `AttestationObjectParser` / `RealAttestationStatementVerifier`
 * 的解析預期：
 *
 * <pre>
 * attestationObject = {
 *   "fmt": "android-key",
 *   "authData": &lt;bytes&gt;,
 *   "attStmt": {
 *     "alg": -7,
 *     "sig": &lt;bytes&gt;,
 *     "x5c": [ &lt;leaf DER bytes&gt;, ...&lt;chain...&gt;, &lt;root DER bytes&gt; ]
 *   }
 * }
 * </pre>
 *
 * x5c 陣列須 leaf-first（Android {@code KeyStore.getCertificateChain()} 回傳順序即為
 * leaf-first，與 fido-server `RealAndroidKeyAttestationChainValidator.extractCertChain` 的
 * 假設一致，不需重新排序）。
 */
object AttestationObjectBuilder {

    private const val FMT_ANDROID_KEY = "android-key"
    private const val COSE_ALG_ES256 = -7

    fun build(
        authenticatorData: ByteArray,
        attStmtSignature: ByteArray,
        certificateChain: List<X509Certificate>,
    ): ByteArray {
        val x5c = certificateChain.map { CborValue.of(it.encoded) }

        val attStmt = CborValue.obj(
            CborValue.of("alg") to CborValue.of(COSE_ALG_ES256),
            CborValue.of("sig") to CborValue.of(attStmtSignature),
            CborValue.of("x5c") to CborValue.arr(x5c),
        )

        val top = CborValue.obj(
            CborValue.of("fmt") to CborValue.of(FMT_ANDROID_KEY),
            CborValue.of("authData") to CborValue.of(authenticatorData),
            CborValue.of("attStmt") to attStmt,
        )

        return Cbor.encode(top)
    }
}
