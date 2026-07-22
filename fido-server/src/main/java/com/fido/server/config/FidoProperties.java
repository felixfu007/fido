package com.fido.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 對應 application.yml 的 {@code fido.*} 設定。
 */
@ConfigurationProperties(prefix = "fido")
public class FidoProperties {

    private Challenge challenge = new Challenge();
    private SessionJwt sessionJwt = new SessionJwt();
    private RateLimit rateLimit = new RateLimit();
    private Attestation attestation = new Attestation();
    private DevSeed devSeed = new DevSeed();
    private Persistence persistence = new Persistence();

    public Challenge getChallenge() {
        return challenge;
    }

    public void setChallenge(Challenge challenge) {
        this.challenge = challenge;
    }

    public SessionJwt getSessionJwt() {
        return sessionJwt;
    }

    public void setSessionJwt(SessionJwt sessionJwt) {
        this.sessionJwt = sessionJwt;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Attestation getAttestation() {
        return attestation;
    }

    public void setAttestation(Attestation attestation) {
        this.attestation = attestation;
    }

    public DevSeed getDevSeed() {
        return devSeed;
    }

    public void setDevSeed(DevSeed devSeed) {
        this.devSeed = devSeed;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    public static class Challenge {
        /** 對齊 CLAUDE.md：Challenge 60 秒時效。 */
        private int ttlSeconds = 60;

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class SessionJwt {
        private String issuer;
        private int ttlSeconds = 120;
        private String kid;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getKid() {
            return kid;
        }

        public void setKid(String kid) {
            this.kid = kid;
        }
    }

    public static class RateLimit {
        private int defaultTps = 100;

        public int getDefaultTps() {
            return defaultTps;
        }

        public void setDefaultTps(int defaultTps) {
            this.defaultTps = defaultTps;
        }
    }

    public static class Attestation {
        /**
         * {@code real}（預設）：使用真實密碼學驗證（{@code RealAttestationStatementVerifier} /
         * {@code RealAndroidKeyAttestationChainValidator} / {@code RealAssertionSignatureVerifier}）。
         * {@code stub}：切回骨架 stub（永遠通過 / 依 {@link Stub} 設定回傳固定結果），僅供
         * 測試環境在不想組出真實 attestation/assertion 密碼學 fixture 時使用；正式部署
         * 不應設為 stub。
         */
        private String mode = "real";

        @NestedConfigurationProperty
        private Stub stub = new Stub();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Stub getStub() {
            return stub;
        }

        public void setStub(Stub stub) {
            this.stub = stub;
        }

        /**
         * 【介面卡骨架】這些設定值控制的是 stub 驗證器的固定回傳結果，
         * 並非真實密碼學驗證。見 com.fido.server.webauthn 套件說明。
         */
        public static class Stub {
            private boolean chainValid = true;
            private String defaultSecurityLevel = "STRONG_BOX";

            public boolean isChainValid() {
                return chainValid;
            }

            public void setChainValid(boolean chainValid) {
                this.chainValid = chainValid;
            }

            public String getDefaultSecurityLevel() {
                return defaultSecurityLevel;
            }

            public void setDefaultSecurityLevel(String defaultSecurityLevel) {
                this.defaultSecurityLevel = defaultSecurityLevel;
            }
        }
    }

    public static class DevSeed {
        private boolean enabled = false;
        private String tenantName;
        private String rpId;
        private String expectedOrigin;
        private String apiKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTenantName() {
            return tenantName;
        }

        public void setTenantName(String tenantName) {
            this.tenantName = tenantName;
        }

        public String getRpId() {
            return rpId;
        }

        public void setRpId(String rpId) {
            this.rpId = rpId;
        }

        public String getExpectedOrigin() {
            return expectedOrigin;
        }

        public void setExpectedOrigin(String expectedOrigin) {
            this.expectedOrigin = expectedOrigin;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Persistence {
        /**
         * {@code jpa}（預設，對齊 CLAUDE.md「獨立 SQL Server 實例」）：啟用
         * {@code com.fido.server.repository.jpa.*}，實際連線的資料庫由
         * {@code spring.datasource.*} 決定（正式環境應指向 SQL Server；本機開發可用
         * H2 MSSQL 相容模式，見 {@code application-h2.yml}，僅存在於 test classpath）。
         * <p>{@code memory}：啟用 {@code com.fido.server.repository.inmemory.*}，
         * 完全不建立任何 DataSource，僅供本機開發/測試骨架使用，正式部署不應設為 memory。
         */
        private String mode = "jpa";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }
}
