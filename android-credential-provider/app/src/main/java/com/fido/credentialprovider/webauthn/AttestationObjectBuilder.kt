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
 *
 * 【鍵值插入順序】下方 entries 刻意依 canonical CBOR 順序書寫（`fmt`/`alg`/`sig`/`x5c`/`authData`
 * 皆已符合「先比編碼後 bytes 長度、再逐 byte 比較」的規則）方便閱讀對照，但正確性不仰賴這個
 * 手動順序——[Cbor.encode] 本身會自動依 canonical CBOR 規則重新排序 map 鍵，即使呼叫端插入
 * 順序寫錯也不會產出非 canonical 的 bytes（見 `Cbor.kt` 檔頭「真機除錯修正」說明：先前
 * `authData`/`attStmt` 插入順序寫反過，只有 Chromium 的嚴格 canonical-CBOR-only 解碼器才會
 * 真正踢掉，fido-server 與本專案既有測試用的 jackson-dataformat-cbor 都不會發現）。
 */
object AttestationObjectBuilder {

    private const val FMT_ANDROID_KEY = "android-key"

    fun build(
        authenticatorData: ByteArray,
        attStmtSignature: ByteArray,
        certificateChain: List<X509Certificate>,
    ): ByteArray {
        val x5c = certificateChain.map { CborValue.of(it.encoded) }

        val attStmt = CborValue.obj(
            CborValue.of("alg") to CborValue.of(CoseKeyEncoder.COSE_ALG_ES256),
            CborValue.of("sig") to CborValue.of(attStmtSignature),
            CborValue.of("x5c") to CborValue.arr(x5c),
        )

        val top = CborValue.obj(
            CborValue.of("fmt") to CborValue.of(FMT_ANDROID_KEY),
            CborValue.of("attStmt") to attStmt,
            CborValue.of("authData") to CborValue.of(authenticatorData),
        )

        return Cbor.encode(top)
    }
}
