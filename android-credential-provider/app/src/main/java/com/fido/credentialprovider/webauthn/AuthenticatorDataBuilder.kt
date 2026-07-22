package com.fido.credentialprovider.webauthn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 組 WebAuthn authenticatorData（固定版面，非 CBOR），格式對齊 fido-server
 * `AuthenticatorDataParser`：
 * {@code rpIdHash(32) + flags(1) + signCount(4, big-endian uint32)
 * [+ aaguid(16) + credIdLen(2) + credId + credentialPublicKey(CBOR)]}
 */
object AuthenticatorDataBuilder {

    const val FLAG_UP: Int = 0x01
    const val FLAG_UV: Int = 0x04
    const val FLAG_AT: Int = 0x40

    /** 本 PoC provider 不隸屬任何 FIDO MDS 認證的 AAGUID，依規範可用全零 16 bytes。 */
    val ZERO_AAGUID: ByteArray = ByteArray(16)

    fun buildForRegistration(
        rpId: String,
        userVerified: Boolean,
        signCount: Long,
        aaguid: ByteArray,
        credentialId: ByteArray,
        credentialPublicKeyCose: ByteArray,
    ): ByteArray {
        var flags = FLAG_UP or FLAG_AT
        if (userVerified) flags = flags or FLAG_UV

        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        out.write(flags)
        out.write(uint32BigEndian(signCount))
        out.write(aaguid)
        out.write(uint16BigEndian(credentialId.size))
        out.write(credentialId)
        out.write(credentialPublicKeyCose)
        return out.toByteArray()
    }

    fun buildForAssertion(rpId: String, userVerified: Boolean, signCount: Long): ByteArray {
        var flags = FLAG_UP
        if (userVerified) flags = flags or FLAG_UV

        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        out.write(flags)
        out.write(uint32BigEndian(signCount))
        return out.toByteArray()
    }

    private fun uint32BigEndian(value: Long): ByteArray =
        ByteBuffer.allocate(4).putInt(value.toInt()).array()

    private fun uint16BigEndian(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
