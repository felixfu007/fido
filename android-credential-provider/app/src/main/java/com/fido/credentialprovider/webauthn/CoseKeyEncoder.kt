package com.fido.credentialprovider.webauthn

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * 把 Android Keystore 產生的 EC (P-256) 公鑰編碼為 COSE_Key（CBOR），對齊
 * fido-server `CoseKeyParser`（`d:\fido\fido-server\src\main\java\com\fido\server\webauthn\CoseKeyParser.java`）
 * 解析時預期的欄位：kty(1)=EC2(2)、alg(3)=ES256(-7)、crv(-1)=P-256(1)、x(-2)、y(-3)，
 * x/y 皆為 32 bytes big-endian 定長（與伺服器 `RealAttestationStatementVerifier` 比對
 * `x5c[0]` 公鑰 vs `credentialPublicKey` 時的還原邏輯一致）。
 */
object CoseKeyEncoder {

    private const val COORDINATE_LENGTH = 32 // P-256 座標定長 32 bytes

    /**
     * COSE 演算法識別碼 ES256（ECDSA w/ SHA-256），對齊 RFC 8152 §8.1 Table 5 與
     * WebAuthn `-7`。單一權威來源常數，供 COSE_Key 的 `alg`(3) 欄位、
     * [AttestationObjectBuilder] 的 `attStmt.alg`，以及
     * [com.fido.credentialprovider.webauthn.RegistrationResponseFields] 交給 Chrome 的
     * `response.publicKeyAlgorithm` 三處共用，避免各自硬寫 `-7` 之後之後改動時互相脫鉤。
     */
    internal const val COSE_ALG_ES256: Int = -7

    fun encodeEcP256(publicKey: ECPublicKey): ByteArray {
        val x = toFixedLength(publicKey.w.affineX, COORDINATE_LENGTH)
        val y = toFixedLength(publicKey.w.affineY, COORDINATE_LENGTH)

        val cose = CborValue.obj(
            CborValue.of(1) to CborValue.of(2),   // kty: EC2
            CborValue.of(3) to CborValue.of(COSE_ALG_ES256),
            CborValue.of(-1) to CborValue.of(1),  // crv: P-256
            CborValue.of(-2) to CborValue.of(x),
            CborValue.of(-3) to CborValue.of(y),
        )
        return Cbor.encode(cose)
    }

    /** 與 fido-server 測試 fixture `WebAuthnCeremonyFixtures.toFixedLength` 邏輯一致。 */
    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == length) return raw
        val fixed = ByteArray(length)
        if (raw.size > length) {
            System.arraycopy(raw, raw.size - length, fixed, 0, length)
        } else {
            System.arraycopy(raw, 0, fixed, length - raw.size, raw.size)
        }
        return fixed
    }
}
