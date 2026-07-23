package com.fido.credentialprovider.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * 【清單項目 3 核心驗證】用 fido-server 實際使用的同一套 CBOR 函式庫
 * （jackson-dataformat-cbor）把本專案手寫 [Cbor]/[AttestationObjectBuilder]/[CoseKeyEncoder]/
 * [AuthenticatorDataBuilder] 產出的 attestationObject 解回來，重放 fido-server
 * `RealAttestationStatementVerifier` / `RealAndroidKeyAttestationChainValidator` 的每一個檢查
 * 步驟，確認位元組層級完全相容——這是回答「自訂 provider 能不能真的產出 android-key
 * attestationObject」這個 PoC 最高風險問題的直接證據。
 *
 * 憑證鏈以本測試自簽的測試 root 組出（比照 fido-server 測試 fixture
 * `TestKeyAttestationFixtures` 的做法），因為在 JVM 單元測試環境沒有真正的 Android Keystore
 * 可用；Android Keystore 本身的行為（StrongBox/TEE fallback、attestation 憑證鏈是否真的可以
 * 這樣組出來）由 [com.fido.credentialprovider.keystore.HardwareKeyManager] 在模擬器/實機上
 * 驗證，兩者互補：這裡驗證「CBOR 結構與簽章驗證邏輯正確」，模擬器驗證「Keystore API 真的能這樣用」。
 */
class AttestationObjectBuilderTest {

    private val cborMapper = ObjectMapper(CBORFactory())
    private val certificateFactory = CertificateFactory.getInstance("X.509")

    private val rootKeyPair = generateEcKeyPair()
    private val rootName = X500Name("CN=Test Root,O=FIDO Android PoC Test")
    private val rootCertificate = selfSignRoot(rootKeyPair, rootName)

    @Test
    fun `attestationObject decodes with jackson-dataformat-cbor and fields are well-formed`() {
        val credentialKeyPair = generateEcKeyPair()
        val credentialId = randomBytes(32)
        val rpId = "shop.example.com"
        val challenge = randomBytes(32)

        val coseKeyBytes = CoseKeyEncoder.encodeEcP256(credentialKeyPair.public as ECPublicKey)
        val authenticatorData = AuthenticatorDataBuilder.buildForRegistration(
            rpId = rpId,
            userVerified = true,
            signCount = 0L,
            aaguid = AuthenticatorDataBuilder.ZERO_AAGUID,
            credentialId = credentialId,
            credentialPublicKeyCose = coseKeyBytes,
        )

        val clientDataJson = ClientDataBuilder.build("webauthn.create", b64Url(challenge), "https://shop.example.com")
        val clientDataHash = sha256(clientDataJson)

        val leafCert = buildLeafCertificate(
            leafPublicKey = credentialKeyPair.public,
            attestationSecurityLevel = 2, // StrongBox
            keymasterSecurityLevel = 2,
            attestationChallenge = challenge,
        )

        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(credentialKeyPair.private)
            update(authenticatorData)
            update(clientDataHash)
        }.sign()

        val attestationObject = AttestationObjectBuilder.build(
            authenticatorData = authenticatorData,
            attStmtSignature = sig,
            certificateChain = listOf(leafCert, rootCertificate),
        )

        // ---- 1. 用 jackson-dataformat-cbor（fido-server 實際使用的函式庫）解回頂層結構 ----
        @Suppress("UNCHECKED_CAST")
        val top = cborMapper.readValue(attestationObject, Map::class.java) as Map<String, Any>
        assertEquals("android-key", top["fmt"])
        assertTrue(top["authData"] is ByteArray)
        assertArrayEquals(authenticatorData, top["authData"] as ByteArray)

        // ---- 1b.【2026-07-23 真機除錯回歸測試】canonical CBOR 鍵順序 ----
        // jackson-dataformat-cbor（本測試使用的解碼器）不強制、也不會回報鍵順序是否為 canonical
        // CBOR——這正是先前 `authData`/`attStmt` 插入順序寫反的 bug 為何能通過這份既有測試、
        // 卻在真機 Chrome（`components/cbor/reader.h` 明載「only accepts canonical CBOR」）當場
        // 被拒收的原因。這裡額外釘住鍵的實際編碼順序（Jackson 對 untyped Map 反序列化保留串流
        // 出現順序），確保 [Cbor] 的自動 canonical 排序持續把關，不會被日後修改悄悄破壞。
        assertEquals(
            "attestationObject 頂層 map 鍵必須是 canonical CBOR 順序（fmt 4bytes < attStmt " +
                "8bytes < authData 9bytes），見 Cbor.kt / AttestationObjectBuilder.kt 檔頭說明",
            listOf("fmt", "attStmt", "authData"),
            top.keys.toList(),
        )

        @Suppress("UNCHECKED_CAST")
        val attStmt = top["attStmt"] as Map<Any, Any>
        assertEquals(-7, (attStmt["alg"] as Number).toInt())
        val decodedSig = attStmt["sig"] as ByteArray
        assertArrayEquals(sig, decodedSig)

        @Suppress("UNCHECKED_CAST")
        val x5c = attStmt["x5c"] as List<ByteArray>
        assertEquals(2, x5c.size)
        assertArrayEquals(leafCert.encoded, x5c[0])
        assertArrayEquals(rootCertificate.encoded, x5c[1])

        // ---- 2. 重放 RealAttestationStatementVerifier 的簽章驗證步驟 ----
        val leafFromChain = certificateFactory.generateCertificate(x5c[0].inputStream()) as X509Certificate
        val verifySig = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(leafFromChain.publicKey)
            update(top["authData"] as ByteArray)
            update(clientDataHash)
        }
        assertTrue("attStmt.sig 必須是 authenticatorData || clientDataHash 在 x5c[0] 公鑰下的合法簽章",
            verifySig.verify(decodedSig))

        // ---- 3. 重放 RealAttestationStatementVerifier 的公鑰一致性檢查：
        //         x5c[0] 公鑰 == authenticatorData.credentialPublicKey ----
        val decodedCoseKeyBytes = extractCredentialPublicKeyCoseBytes(top["authData"] as ByteArray)
        val reconstructedPublicKey = decodeCoseEc2PublicKey(decodedCoseKeyBytes)
        assertArrayEquals(
            "x5c[0] 公鑰必須與 authenticatorData.credentialPublicKey 編碼後一致",
            leafFromChain.publicKey.encoded,
            reconstructedPublicKey.encoded,
        )
        assertArrayEquals(credentialKeyPair.public.encoded, reconstructedPublicKey.encoded)

        // ---- 4. 重放 RealAndroidKeyAttestationChainValidator 的憑證鏈簽章/效期/challenge 檢查 ----
        leafFromChain.checkValidity()
        rootCertificate.checkValidity()
        leafFromChain.verify(rootCertificate.publicKey) // leaf 由 root 簽發
        rootCertificate.verify(rootCertificate.publicKey) // root 自簽完整性

        val keyDescription = parseKeyDescription(leafFromChain)
        assertArrayEquals(challenge, keyDescription.attestationChallenge)
        assertEquals(2, keyDescription.attestationSecurityLevel) // STRONG_BOX
        assertEquals(2, keyDescription.keymasterSecurityLevel)
    }

    // ------------------------------------------------------------------
    // helpers（刻意獨立於本專案 main 程式碼之外撰寫，模擬「伺服器端獨立驗證」，
    // 避免測試對 production 程式碼的實作細節產生循環依賴式的假通過）
    // ------------------------------------------------------------------

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun selfSignRoot(rootKeyPair: KeyPair, rootName: X500Name): X509Certificate {
        val now = Instant.now()
        val serial = BigInteger.valueOf(now.toEpochMilli())
        val builder = JcaX509v3CertificateBuilder(
            rootName, serial,
            Date.from(now.minus(Duration.ofMinutes(5))),
            Date.from(now.plus(Duration.ofDays(3650))),
            rootName, rootKeyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun buildLeafCertificate(
        leafPublicKey: java.security.PublicKey,
        attestationSecurityLevel: Int,
        keymasterSecurityLevel: Int,
        attestationChallenge: ByteArray,
    ): X509Certificate {
        val now = Instant.now()
        val subject = X500Name("CN=Test Leaf,O=FIDO Android PoC Test")
        val serial = BigInteger.valueOf(now.toEpochMilli() + 1)

        val builder = JcaX509v3CertificateBuilder(
            rootName, serial,
            Date.from(now.minus(Duration.ofMinutes(5))),
            Date.from(now.plus(Duration.ofDays(1))),
            subject, leafPublicKey,
        )

        val keyDescription = ASN1EncodableVector()
        keyDescription.add(ASN1Integer(4))
        keyDescription.add(ASN1Enumerated(attestationSecurityLevel))
        keyDescription.add(ASN1Integer(4))
        keyDescription.add(ASN1Enumerated(keymasterSecurityLevel))
        keyDescription.add(DEROctetString(attestationChallenge))
        keyDescription.add(DEROctetString(ByteArray(0)))
        keyDescription.add(DERSequence())
        keyDescription.add(DERSequence())
        builder.addExtension(
            ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, DERSequence(keyDescription),
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private data class KeyDescription(
        val attestationSecurityLevel: Int,
        val keymasterSecurityLevel: Int,
        val attestationChallenge: ByteArray,
    )

    private fun parseKeyDescription(leaf: X509Certificate): KeyDescription {
        val extensionValue = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17")
            ?: error("leaf 憑證缺少 Key Attestation extension")
        val outer = org.bouncycastle.asn1.ASN1OctetString.getInstance(extensionValue)
        val sequence = org.bouncycastle.asn1.ASN1Sequence.getInstance(outer.octets)
        val attestationSecurityLevel = org.bouncycastle.asn1.ASN1Enumerated.getInstance(sequence.getObjectAt(1)).value.toInt()
        val keymasterSecurityLevel = org.bouncycastle.asn1.ASN1Enumerated.getInstance(sequence.getObjectAt(3)).value.toInt()
        val challenge = org.bouncycastle.asn1.ASN1OctetString.getInstance(sequence.getObjectAt(4)).octets
        return KeyDescription(attestationSecurityLevel, keymasterSecurityLevel, challenge)
    }

    /** 依 WebAuthn authenticatorData 固定版面手動解出 credentialPublicKey 的 CBOR bytes（假設 ED=false）。 */
    private fun extractCredentialPublicKeyCoseBytes(authData: ByteArray): ByteArray {
        var offset = 32 + 1 + 4 // rpIdHash + flags + signCount
        offset += 16 // aaguid
        val credIdLen = ((authData[offset].toInt() and 0xFF) shl 8) or (authData[offset + 1].toInt() and 0xFF)
        offset += 2 + credIdLen
        return authData.copyOfRange(offset, authData.size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeCoseEc2PublicKey(coseKeyBytes: ByteArray): java.security.PublicKey {
        val map = cborMapper.readValue(coseKeyBytes, Map::class.java) as Map<Any, Any>
        val x = field(map, -2) as ByteArray
        val y = field(map, -3) as ByteArray
        val algParams = AlgorithmParameters.getInstance("EC")
        algParams.init(ECGenParameterSpec("secp256r1"))
        val ecSpec = algParams.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
        return java.security.KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecSpec))
    }

    private fun field(map: Map<Any, Any>, label: Int): Any? = map[label] ?: map[label.toString()]

    private fun randomBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        java.security.SecureRandom().nextBytes(b)
        return b
    }

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun b64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
