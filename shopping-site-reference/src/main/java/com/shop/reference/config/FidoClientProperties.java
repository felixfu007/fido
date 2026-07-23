package com.shop.reference.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 對應 application.yml 的 {@code fido-client.*} 設定：購物網站後端要如何呼叫
 * fido-server（base URL、API Key）以及要如何驗證 fido-server 簽發的 session JWT
 * （iss / aud / JWKS，見 docs/api-contract.md 1.3）。
 *
 * <p>{@code apiKey} / {@code tenantId} 直接沿用 fido-server 開發環境的
 * {@code DevDataSeeder} 種子租戶（{@code dev-api-key-00000000000000000000}，
 * {@code rpId=shop.example.com}），本專案不自己建租戶邏輯（對齊任務範圍界定）。
 */
@ConfigurationProperties(prefix = "fido-client")
public class FidoClientProperties {

    /** fido-server base URL，例如 {@code http://localhost:8443}。 */
    private String baseUrl;

    /** 租戶 API Key（{@code X-API-Key}）。 */
    private String apiKey;

    /**
     * 選填：租戶 UUID（{@code X-Tenant-Id}），僅作交叉檢查用（api-contract.md 1.2 D2）。
     * 留空則不送出該 header。
     */
    private String tenantId;

    /**
     * 驗證 session JWT 的 {@code iss}：必須等於 fido-server 端 {@code fido.session-jwt.issuer}
     * 設定值。購物網站端無法信任 JWT 內任何未經驗證的欄位，{@code iss} 本身也要核對，
     * 避免有人拿別的（甚至假的）簽發者簽出的 token 來魚目混珠。
     */
    private String expectedIssuer;

    /**
     * 驗證 session JWT 的 {@code aud}：必須等於「自己的網域」（購物網站的 rp_id，這裡等於
     * 種子租戶的 {@code shop.example.com}）。對齊 api-contract.md 1.3：「購物網站以...
     * 自行校驗 iss / aud（= 自己網域）」。
     */
    private String expectedAudience;

    /** JWKS 快取有效期（秒），超過才重新呼叫 fido-server 的 JWKS 端點。 */
    private long jwksCacheTtlSeconds = 300;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getExpectedIssuer() {
        return expectedIssuer;
    }

    public void setExpectedIssuer(String expectedIssuer) {
        this.expectedIssuer = expectedIssuer;
    }

    public String getExpectedAudience() {
        return expectedAudience;
    }

    public void setExpectedAudience(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    public long getJwksCacheTtlSeconds() {
        return jwksCacheTtlSeconds;
    }

    public void setJwksCacheTtlSeconds(long jwksCacheTtlSeconds) {
        this.jwksCacheTtlSeconds = jwksCacheTtlSeconds;
    }
}
