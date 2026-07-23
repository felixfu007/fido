package com.shop.reference.authentication.jwt;

import com.shop.reference.config.FidoClientProperties;
import com.shop.reference.fidoclient.FidoServerClient;
import com.shop.reference.fidoclient.dto.JwkSetResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 購物網站後端驗證 fido-server 簽發的短時效 session JWT（docs/api-contract.md 1.3）。
 *
 * <p><b>這是整個串接裡最關鍵的信任邊界</b>：登入 ceremony 的密碼學驗證（assertion 簽章、
 * sign counter、attestation）全部發生在 fido-server 那一側；購物網站後端唯一能獨立確認
 * 「使用者剛通過 FIDO 硬體驗證」的方法，就是拿 fido-server 的公鑰（JWKS）自己驗證這枚 JWT
 * 的簽章，而不是相信 HTTP 回應裡的 {@code verified:true} 這種可被竄改或偽冒的欄位。
 * 以下每一個檢核步驟都對應一種具體的攻擊/失效情境，缺一不可：
 *
 * <ul>
 *   <li><b>簽章驗證</b>：防止有人偽造一枚宣稱「已驗證」的假 JWT（沒有 fido-server 私鑰就
 *       簽不出合法簽章）。</li>
 *   <li><b>{@code iss}（issuer）比對</b>：防止拿到「別的、甚至不相關系統」簽出的合法 JWT
 *       混進來（例如同網路內另一套系統也用 ES256 簽 JWT）。</li>
 *   <li><b>{@code aud}（audience）比對 = 自己的網域</b>：防止 token 被錯誤地用在不該用的
 *       租戶身上——多租戶平台上，A 租戶的使用者登入後拿到的 JWT，{@code aud} 是 A 租戶的
 *       {@code rp_id}；如果 B 租戶的後端沒有檢查 {@code aud} 就照單全收，等於任何租戶都能
 *       冒用別租戶的登入結果。</li>
 *   <li><b>{@code exp}（過期時間）比對</b>：120 秒短效是設計上刻意讓「轉發竊用」窗口極短
 *       （見 api-contract.md D5）；購物網站這端如果沒真的檢查 exp，這個設計就形同虛設。</li>
 *   <li><b>{@code jti} 一次性消費</b>：即使 token 沒過期，同一枚 token 也不該被重複拿來建立
 *       第二次 session（例如遭中間人重放）；本類別用一個有界的記憶體集合追蹤已使用過的
 *       {@code jti}（僅供示範，正式環境應改用有 TTL 的共享儲存，如 Redis，否則多實例部署
 *       時各節點看到的「已用過 jti」不會同步，也會隨著程序重啟遺失）。</li>
 * </ul>
 */
@Component
public class FidoSessionJwtValidator {

    private static final Logger log = LoggerFactory.getLogger(FidoSessionJwtValidator.class);

    private final FidoServerClient fidoServerClient;
    private final FidoClientProperties properties;

    /** kid -> 對應公鑰，來自 fido-server JWKS 端點的快取（避免每次登入都打一次 JWKS）。 */
    private final ConcurrentMap<String, ECPublicKey> jwksCache = new ConcurrentHashMap<>();
    private volatile Instant jwksCacheRefreshedAt = Instant.EPOCH;
    private final ReentrantLock jwksRefreshLock = new ReentrantLock();

    /**
     * 已消費過的 {@code jti}（僅示範用途，見 class 說明）。用 token 的 TTL（120 秒）+ 一段
     * 寬限期估算何時可以從集合中移除，避免無限增長；示範規模下不做這個清理也不會造成問題，
     * 但仍附上供讀者理解正式環境該怎麼做。
     */
    private final ConcurrentMap<String, Instant> consumedJti = new ConcurrentHashMap<>();

    public FidoSessionJwtValidator(FidoServerClient fidoServerClient, FidoClientProperties properties) {
        this.fidoServerClient = fidoServerClient;
        this.properties = properties;
    }

    /**
     * 驗證 fido-server 簽發的 session JWT，全部通過才回傳 {@link ValidatedFidoSession}；
     * 任何一項檢核失敗一律拋出 {@link JwtValidationException}，呼叫端不應嘗試「部分信任」。
     */
    public ValidatedFidoSession validate(String jwt) {
        Jws<Claims> parsed;
        try {
            parsed = Jwts.parser()
                    .keyLocator(header -> resolveKey(header.get("kid") == null ? null : header.get("kid").toString()))
                    .requireIssuer(properties.getExpectedIssuer())
                    .build()
                    .parseSignedClaims(jwt);
        } catch (ExpiredJwtException e) {
            throw new JwtValidationException("EXPIRED", "Session JWT 已過期（exp 已過），拒絕建立購物網站 session。", e);
        } catch (JwtException e) {
            throw new JwtValidationException("SIGNATURE_OR_CLAIM_INVALID",
                    "Session JWT 簽章或必要 claim（iss）驗證失敗：" + e.getMessage(), e);
        }

        Claims claims = parsed.getPayload();

        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(properties.getExpectedAudience())) {
            throw new JwtValidationException("AUDIENCE_MISMATCH",
                    "Session JWT 的 aud=" + audience + " 不含本站網域 " + properties.getExpectedAudience() + "。");
        }

        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw new JwtValidationException("MISSING_JTI", "Session JWT 缺少 jti，無法做一次性消費防重放。");
        }
        Instant previouslyConsumedAt = consumedJti.putIfAbsent(jti, Instant.now());
        if (previouslyConsumedAt != null) {
            throw new JwtValidationException("JTI_REPLAYED",
                    "Session JWT 的 jti=" + jti + " 先前已被使用過（首次使用於 " + previouslyConsumedAt + "），疑似重放攻擊。");
        }

        String externalUserId = claims.getSubject();
        String tenantId = claims.get("tid", String.class);
        String credentialId = claims.get("cid", String.class);
        String deviceId = claims.get("did", String.class);
        Long authTime = claims.get("auth_time", Long.class);

        if (externalUserId == null || externalUserId.isBlank()) {
            throw new JwtValidationException("MISSING_SUB", "Session JWT 缺少 sub（externalUserId）。");
        }

        log.info("Session JWT 驗證通過：sub={}, tid={}, did={}, jti={}", externalUserId, tenantId, deviceId, jti);
        return new ValidatedFidoSession(externalUserId, tenantId, credentialId, deviceId,
                authTime == null ? 0L : authTime, jti);
    }

    /**
     * 依 {@code kid} 從快取取公鑰；快取沒有（可能是新 token 用了輪替後的新 key，見
     * docs/api-contract.md D8）才回頭打一次 fido-server 的 JWKS 端點重新整理快取。
     */
    private Key resolveKey(String kid) {
        if (kid == null) {
            throw new JwtValidationException("MISSING_KID", "Session JWT header 缺少 kid，無法選擇驗簽公鑰。");
        }
        ECPublicKey cached = jwksCache.get(kid);
        if (cached != null && !cacheExpired()) {
            return cached;
        }
        refreshJwks();
        ECPublicKey key = jwksCache.get(kid);
        if (key == null) {
            throw new JwtValidationException("UNKNOWN_KID",
                    "找不到 kid=" + kid + " 對應的公鑰（可能是尚未同步到的金鑰輪替，或偽造的 kid）。");
        }
        return key;
    }

    private boolean cacheExpired() {
        return Instant.now().isAfter(jwksCacheRefreshedAt.plusSeconds(properties.getJwksCacheTtlSeconds()));
    }

    private void refreshJwks() {
        jwksRefreshLock.lock();
        try {
            if (!jwksCache.isEmpty() && !cacheExpired()) {
                return; // 另一個執行緒已經在等鎖的期間刷新過了
            }
            JwkSetResponse jwks = fidoServerClient.jwks();
            for (JwkSetResponse.Jwk jwk : jwks.keys()) {
                jwksCache.put(jwk.kid(), toEcPublicKey(jwk));
            }
            jwksCacheRefreshedAt = Instant.now();
        } finally {
            jwksRefreshLock.unlock();
        }
    }

    /**
     * 把 JWKS 的 {@code x}/{@code y}（base64url，無 padding，固定長度 32 bytes，對應
     * {@code JwtService.fixedLengthBase64Url} 的編碼方式）還原成 P-256 {@link ECPublicKey}。
     */
    private static ECPublicKey toEcPublicKey(JwkSetResponse.Jwk jwk) {
        try {
            byte[] xBytes = Base64.getUrlDecoder().decode(jwk.x());
            byte[] yBytes = Base64.getUrlDecoder().decode(jwk.y());
            BigInteger x = new BigInteger(1, xBytes);
            BigInteger y = new BigInteger(1, yBytes);
            ECPoint point = new ECPoint(x, y);

            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecParameterSpec = params.getParameterSpec(ECParameterSpec.class);

            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(point, ecParameterSpec));
        } catch (Exception e) {
            throw new JwtValidationException("MALFORMED_JWK", "無法從 JWKS 還原公鑰 kid=" + jwk.kid(), e);
        }
    }
}
