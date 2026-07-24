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
    private CrossDevice crossDevice = new CrossDevice();

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

    public CrossDevice getCrossDevice() {
        return crossDevice;
    }

    public void setCrossDevice(CrossDevice crossDevice) {
        this.crossDevice = crossDevice;
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

        @NestedConfigurationProperty
        private PocTrust pocTrust = new PocTrust();

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

        public PocTrust getPocTrust() {
            return pocTrust;
        }

        public void setPocTrust(PocTrust pocTrust) {
            this.pocTrust = pocTrust;
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

        /**
         * 【PoC 專用信任設定，與 {@link #mode} 完全獨立】{@code mode} 控制「要不要做真實密碼學
         * 驗證」；本設定控制「除了內建 Google 官方 root 之外，要不要額外信任一組指定的測試
         * root」——用於 Android 模擬器沒有真正 StrongBox/TEE、其 Key Attestation 憑證鏈只會
         * 鏈到模擬器自產的測試 root（而非 Google root）的情境，讓 PoC 能在
         * {@code fido.attestation.mode=real}（完整密碼學驗證，含 challenge 綁定與
         * securityLevel 判讀）下走到「憑證鏈信任」這一步之後。
         *
         * <p><b>預設值 {@code enabled=false}</b>：不論任何 profile，只要沒有明確在設定檔或啟動
         * 參數開啟，{@link com.fido.server.webauthn.TrustedRootCertificateStore} 的行為與此
         * 設定新增之前完全一致（只信任內建 Google root），正式部署路徑不受影響。
         */
        public static class PocTrust {
            private boolean enabled = false;
            private String extraRootsLocation = "file:./poc-trust-roots/*.pem";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getExtraRootsLocation() {
                return extraRootsLocation;
            }

            public void setExtraRootsLocation(String extraRootsLocation) {
                this.extraRootsLocation = extraRootsLocation;
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

    /**
     * 情境三（跨裝置 QR transaction confirmation）設定，見 CLAUDE.md「桌機 QR 掃碼跨裝置登入」
     * 決策定案與 {@code docs/decisions/qr-cross-device-login-design.md}。
     */
    public static class CrossDevice {
        /**
         * xdev session / 其包住的 {@code auth_challenges} 列 TTL（秒）。CLAUDE.md「Challenge
         * 時效 60 秒」的 S6 放寬值，**僅此 ceremony type**，同裝置流程仍為
         * {@link Challenge#ttlSeconds} 的 60 秒預設，不受影響。
         */
        private int ttlSeconds = 120;

        /**
         * QR 內容 {@code https://<fido-app-link-host>/x/<xdevId>} 的網域部分（平台營運方自有、
         * 已完成 Digital Asset Links 驗證的 App Link 網域，非租戶網域）。見設計文件 3.1 節。
         */
        private String appLinkHost = "REPLACE_WITH_FIDO_APP_LINK_HOST";

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getAppLinkHost() {
            return appLinkHost;
        }

        public void setAppLinkHost(String appLinkHost) {
            this.appLinkHost = appLinkHost;
        }
    }
}
