package com.fido.server.service;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.SigningKey;
import com.fido.server.repository.SigningKeyRepository;
import io.jsonwebtoken.Jwts;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 【真實實作】session JWT（ES256）簽發與 JWKS 公鑰發布。
 * 對齊 CLAUDE.md / api-contract.md D4/D5：ES256、{@code exp = iat + 120 秒}。
 *
 * <p><b>金鑰持久化（db-schema.md 第 10 節 / DB18）</b>：簽章金鑰持久化於 {@code signing_keys}
 * 表，所有連同一資料庫的實例共享同一把 {@code ACTIVE} 金鑰，解決「記憶體金鑰重啟即換、多實例
 * JWKS 不一致」的部署缺口。啟動時載入唯一 {@code ACTIVE} 列；全新資料庫首啟則自動產生並
 * INSERT，多實例並發首啟時依賴 {@code UX_signkey_one_active} filtered unique index，敗者
 * （撞到唯一鍵衝突者）改讀既有列。v1 僅做「持久化 + 單一有效金鑰」，不做自動排程輪替，手動
 * 輪替見 {@code com.fido.server.admin.AdminCliRunner} 的 {@code rotate-signing-key} 指令。
 */
@Service
public class JwtService {

    private final FidoProperties properties;
    private final SigningKeyRepository signingKeyRepository;
    private final LoadedKey activeKey;

    public JwtService(FidoProperties properties, SigningKeyRepository signingKeyRepository,
                       SigningKeyFactory signingKeyFactory) {
        this.properties = properties;
        this.signingKeyRepository = signingKeyRepository;
        this.activeKey = loadOrCreateActiveKey(signingKeyFactory);
    }

    private LoadedKey loadOrCreateActiveKey(SigningKeyFactory signingKeyFactory) {
        return signingKeyRepository.findActive()
                .map(JwtService::toLoadedKey)
                .orElseGet(() -> createAndPersistNewActiveKey(signingKeyFactory));
    }

    /**
     * 全新資料庫首次啟動：產生新金鑰並 INSERT 為 ACTIVE。若因多實例並發首啟撞到
     * {@code UX_signkey_one_active} 唯一索引違反（{@link DataIntegrityViolationException}），
     * 代表另一個實例已搶先建立，改重新查詢並使用既有的 ACTIVE 列，而不是讓啟動失敗。
     */
    private LoadedKey createAndPersistNewActiveKey(SigningKeyFactory signingKeyFactory) {
        SigningKey newKey = signingKeyFactory.generate(properties.getSessionJwt().getKid());
        try {
            SigningKey saved = signingKeyRepository.save(newKey);
            return toLoadedKey(saved);
        } catch (DataIntegrityViolationException concurrentFirstBootConflict) {
            return signingKeyRepository.findActive()
                    .map(JwtService::toLoadedKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "插入 signing key 時撞到 UX_signkey_one_active 唯一索引衝突，"
                                    + "但重新查詢仍找不到任何 ACTIVE 金鑰，資料狀態異常，需人工排查。",
                            concurrentFirstBootConflict));
        }
    }

    private static LoadedKey toLoadedKey(SigningKey signingKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(signingKey.getPrivateKey()));
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(signingKey.getPublicKey()));
            return new LoadedKey(signingKey.getKid(), new KeyPair(publicKey, privateKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("無法還原 signing_keys 資料列（kid=" + signingKey.getKid() + "）的 EC P-256 金鑰對", e);
        }
    }

    /**
     * 簽發登入成功後交給購物網站的短時效 session JWT（api-contract.md 1.3）。
     * header 的 {@code kid} 一律採用啟動時從 {@code signing_keys} 載入/產生的那把金鑰的
     * {@code kid}（不再直接讀 {@code fido.session-jwt.kid} 設定值——該設定值僅在「全新產生
     * 金鑰、且沒有既有 ACTIVE 列」時，做為初始 {@code kid} 的命名依據，見
     * {@link #createAndPersistNewActiveKey}）。
     */
    public IssuedToken issue(String rpId, String externalUserId, String tenantUid,
                              String credentialIdBase64Url, String deviceIdString) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getSessionJwt().getTtlSeconds());
        String jti = "jti_" + UUID.randomUUID();

        String token = Jwts.builder()
                .header().keyId(activeKey.kid()).and()
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
                .signWith(activeKey.keyPair().getPrivate(), Jwts.SIG.ES256)
                .compact();

        return new IssuedToken(token, properties.getSessionJwt().getTtlSeconds(), jti);
    }

    /**
     * 對應 api-contract.md 3.3 JWKS 端點。回傳資料庫內所有 {@code ACTIVE}+{@code RETIRED}
     * 金鑰的公鑰（db-schema.md 第 10 節）：單一有效金鑰時即長度 1 的清單；輪替後短暫並存新舊
     * 公鑰，讓過渡期間舊 {@code kid} 簽出、尚未過期（&le;120 秒）的 JWT 仍可驗簽。
     */
    public JwkSet jwks() {
        List<Jwk> jwks = signingKeyRepository.findAll().stream()
                .map(JwtService::toJwk)
                .collect(Collectors.toList());
        return new JwkSet(jwks);
    }

    private static Jwk toJwk(SigningKey signingKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            ECPublicKey ecPublicKey = (ECPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(signingKey.getPublicKey()));
            ECPoint point = ecPublicKey.getW();
            String x = fixedLengthBase64Url(point.getAffineX().toByteArray(), 32);
            String y = fixedLengthBase64Url(point.getAffineY().toByteArray(), 32);
            return new Jwk("EC", "P-256", signingKey.getKid(), x, y, "sig", "ES256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("無法還原 signing_keys 資料列（kid=" + signingKey.getKid() + "）的公鑰供 JWKS 發布", e);
        }
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

    /** 啟動時載入/產生的簽發用金鑰：{@code kid} + 還原後的 {@link KeyPair}。 */
    private record LoadedKey(String kid, KeyPair keyPair) {
    }
}
