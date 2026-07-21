package com.fido.server.service;

import com.fido.server.config.FidoProperties;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

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

/**
 * 【真實實作】session JWT（ES256）簽發與 JWKS 公鑰發布。
 * 對齊 CLAUDE.md / api-contract.md D4/D5：ES256、{@code exp = iat + 120 秒}。
 *
 * <p><b>金鑰管理（骨架限制，非密碼學驗證問題，屬部署議題）</b>：本骨架於應用程式啟動時
 * 於記憶體產生一組 EC P-256 keypair，程序重啟即更換金鑰、先前簽出的 token 全部失效。
 * 正式版金鑰應改為持久化保存並支援依 {@code kid} 輪替、新舊公鑰於 JWKS 並存一段時間以
 * 平滑過渡（devops-engineer 決定金鑰存放與輪替機制，本類別介面不需因此變動）。
 */
@Service
public class JwtService {

    private final FidoProperties properties;
    private final KeyPair keyPair;

    public JwtService(FidoProperties properties) {
        this.properties = properties;
        this.keyPair = generateKeyPair();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("無法產生 EC P-256 金鑰對", e);
        }
    }

    /**
     * 簽發登入成功後交給購物網站的短時效 session JWT（api-contract.md 1.3）。
     */
    public IssuedToken issue(String rpId, String externalUserId, String tenantUid,
                              String credentialIdBase64Url, String deviceIdString) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getSessionJwt().getTtlSeconds());
        String jti = "jti_" + UUID.randomUUID();

        String token = Jwts.builder()
                .header().keyId(properties.getSessionJwt().getKid()).and()
                .issuer(properties.getSessionJwt().getIssuer())
                .audience().add(rpId).and()
                .subject(externalUserId)
                .claim("tid", tenantUid)
                .claim("cid", credentialIdBase64Url)
                .claim("did", deviceIdString)
                .claim("amr", List.of("fido", "hwk"))
                .claim("auth_time", now.getEpochSecond())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(jti)
                .signWith(keyPair.getPrivate(), Jwts.SIG.ES256)
                .compact();

        return new IssuedToken(token, properties.getSessionJwt().getTtlSeconds(), jti);
    }

    /**
     * 對應 api-contract.md 3.3 JWKS 端點。
     */
    public JwkSet jwks() {
        ECPublicKey ecPublicKey = (ECPublicKey) keyPair.getPublic();
        ECPoint point = ecPublicKey.getW();
        String x = fixedLengthBase64Url(point.getAffineX().toByteArray(), 32);
        String y = fixedLengthBase64Url(point.getAffineY().toByteArray(), 32);
        Jwk jwk = new Jwk("EC", "P-256", properties.getSessionJwt().getKid(), x, y, "sig", "ES256");
        return new JwkSet(List.of(jwk));
    }

    /**
     * BigInteger.toByteArray() 可能多帶一個前導 0x00 sign byte，或長度不足，
     * 需正規化為固定長度（P-256 座標固定 32 bytes）後再 base64url 編碼。
     */
    private static String fixedLengthBase64Url(byte[] signedBytes, int fixedLength) {
        byte[] normalized = new byte[fixedLength];
        int srcPos = Math.max(0, signedBytes.length - fixedLength);
        int destPos = Math.max(0, fixedLength - signedBytes.length);
        int length = Math.min(signedBytes.length, fixedLength);
        System.arraycopy(signedBytes, srcPos, normalized, destPos, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(normalized);
    }

    public record IssuedToken(String token, int expiresIn, String jti) {
    }

    public record Jwk(String kty, String crv, String kid, String x, String y, String use, String alg) {
    }

    public record JwkSet(List<Jwk> keys) {
    }
}
