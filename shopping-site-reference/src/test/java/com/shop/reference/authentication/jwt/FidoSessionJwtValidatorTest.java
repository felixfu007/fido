package com.shop.reference.authentication.jwt;

import com.shop.reference.config.FidoClientProperties;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.JwkSetResponse;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 對應任務要求「JWT 驗證邏輯尤其重要」：涵蓋合法 JWT 通過、簽章竄改被拒、過期被拒、
 * aud/iss 不符被拒，外加 jti 重放被拒 / 缺 kid 被拒 / 未知 kid 被拒。
 *
 * <p>刻意不依賴真的啟動一份 fido-server：本測試自己產生一組 EC P-256 keypair 模擬
 * fido-server 的簽章金鑰（簽發流程刻意複製 {@code JwtService.issue} 的 claims 結構，
 * 但這是「模擬 fido-server 的輸出格式」，不是引用 fido-server 的程式碼），並用 mock 的
 * {@link FidoServerClient#jwks()} 提供對應公鑰，讓測試完全在 JVM 內、不需網路即可驗證
 * 購物網站端「獨立驗證 JWT」這個信任邊界本身的正確性。
 */
class FidoSessionJwtValidatorTest {

    private static final String ISSUER = "https://fido.example.internal";
    private static final String AUDIENCE = "shop.example.com";
    private static final String KID = "test-kid-1";

    private KeyPair keyPair;
    private FidoServerClient fidoServerClient;
    private FidoSessionJwtValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = gen.generateKeyPair();

        FidoClientProperties properties = new FidoClientProperties();
        properties.setExpectedIssuer(ISSUER);
        properties.setExpectedAudience(AUDIENCE);
        properties.setJwksCacheTtlSeconds(300);

        fidoServerClient = mock(FidoServerClient.class);
        when(fidoServerClient.jwks()).thenReturn(new JwkSetResponse(List.of(toJwk((ECPublicKey) keyPair.getPublic()))));

        validator = new FidoSessionJwtValidator(fidoServerClient, properties);
    }

    @Test
    void validJwt_isAcceptedAndClaimsExtracted() {
        String token = buildToken(t -> t);

        ValidatedFidoSession session = validator.validate(token);

        assertThat(session.externalUserId()).isEqualTo("u-10023");
        assertThat(session.tenantId()).isEqualTo("tenant-uid-1");
        assertThat(session.credentialId()).isEqualTo("cred-abc");
        assertThat(session.deviceId()).isEqualTo("dev-xyz");
        assertThat(session.jti()).isNotBlank();
    }

    @Test
    void tamperedSignature_isRejected() {
        String token = buildToken(t -> t);
        // 竄改最後一段（簽章）幾個字元，模擬中間人篡改或偽造。
        String[] parts = token.split("\\.");
        // 刻意挑簽章字串「中段」字元竄改，而不是最後一個字元 —— base64url 編碼的最後一個字元
        // 可能只承載原始 bytes 之外的填補位元，翻轉它有機率剛好不改變解碼後的實際 byte
        // （曾經在這裡踩過：flip 最後一個字元導致這條測試偶發性地沒有真的破壞簽章）。
        String tamperedSig = flipMiddleChar(parts[2]);
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSig;

        assertThatThrownBy(() -> validator.validate(tampered))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("SIGNATURE_OR_CLAIM_INVALID");
    }

    @Test
    void expiredJwt_isRejected() {
        Instant past = Instant.now().minusSeconds(600);
        String token = buildTokenAt(past, past.plusSeconds(120));

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("EXPIRED");
    }

    @Test
    void wrongAudience_isRejected() {
        String token = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .audience().add("some-other-tenant.example.com").and()
                .subject("u-10023")
                .claim("tid", "tenant-uid-1")
                .claim("cid", "cred-abc")
                .claim("did", "dev-xyz")
                .claim("amr", List.of("fido", "hwk"))
                .claim("auth_time", Instant.now().getEpochSecond())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .id("jti_" + UUID.randomUUID())
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("AUDIENCE_MISMATCH");
    }

    @Test
    void wrongIssuer_isRejected() {
        String token = Jwts.builder()
                .header().keyId(KID).and()
                .issuer("https://not-fido-server.evil.example")
                .audience().add(AUDIENCE).and()
                .subject("u-10023")
                .claim("tid", "tenant-uid-1")
                .claim("cid", "cred-abc")
                .claim("did", "dev-xyz")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .id("jti_" + UUID.randomUUID())
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("SIGNATURE_OR_CLAIM_INVALID"); // requireIssuer() 失敗屬於 JwtException 分支
    }

    @Test
    void replayedJti_isRejectedOnSecondUse() {
        String token = buildToken(t -> t);

        validator.validate(token); // 第一次使用：應該成功
        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("JTI_REPLAYED");
    }

    @Test
    void missingKidHeader_isRejected() {
        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject("u-10023")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .id("jti_" + UUID.randomUUID())
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("MISSING_KID");
    }

    @Test
    void unknownKid_isRejected() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair otherKeyPair = gen.generateKeyPair();

        String token = Jwts.builder()
                .header().keyId("kid-not-in-jwks").and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject("u-10023")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .id("jti_" + UUID.randomUUID())
                .signWith(otherKeyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        assertThatThrownBy(() -> validator.validate(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getReasonCode())
                .isEqualTo("UNKNOWN_KID");
    }

    private String buildToken(java.util.function.UnaryOperator<Instant> noop) {
        Instant now = Instant.now();
        return buildTokenAt(now, now.plusSeconds(120));
    }

    private String buildTokenAt(Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject("u-10023")
                .claim("tid", "tenant-uid-1")
                .claim("cid", "cred-abc")
                .claim("did", "dev-xyz")
                .claim("amr", List.of("fido", "hwk"))
                .claim("auth_time", issuedAt.getEpochSecond())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id("jti_" + UUID.randomUUID())
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();
    }

    private static String flipMiddleChar(String base64urlSegment) {
        StringBuilder sb = new StringBuilder(base64urlSegment);
        int mid = sb.length() / 2;
        char c = sb.charAt(mid);
        sb.setCharAt(mid, c == 'A' ? 'B' : 'A');
        return sb.toString();
    }

    /** 對應 JwtService.jwks() 的編碼方式（固定長度 32 bytes、無 padding base64url）。 */
    private static JwkSetResponse.Jwk toJwk(ECPublicKey publicKey) {
        ECPoint point = publicKey.getW();
        String x = fixedLengthBase64Url(point.getAffineX().toByteArray(), 32);
        String y = fixedLengthBase64Url(point.getAffineY().toByteArray(), 32);
        return new JwkSetResponse.Jwk("EC", "P-256", KID, x, y, "sig", "ES256");
    }

    private static String fixedLengthBase64Url(byte[] signedBytes, int fixedLength) {
        byte[] normalized = new byte[fixedLength];
        int srcPos = Math.max(0, signedBytes.length - fixedLength);
        int destPos = Math.max(0, fixedLength - signedBytes.length);
        int length = Math.min(signedBytes.length, fixedLength);
        System.arraycopy(signedBytes, srcPos, normalized, destPos, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(normalized);
    }
}
