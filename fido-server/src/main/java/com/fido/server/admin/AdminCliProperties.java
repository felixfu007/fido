package com.fido.server.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 對應 {@code admin-cli} profile 的 {@code --fido.admin.*} 命令列參數（見 CLAUDE.md
 * 「租戶開通 / 簽章金鑰 CLI 決策」）。{@code FidoServerApplication} 已有
 * {@code @ConfigurationPropertiesScan}，本類別不需另外註冊。
 */
@ConfigurationProperties(prefix = "fido.admin")
public class AdminCliProperties {

    /** {@code create-tenant} / {@code add-app-binding} / {@code rotate-signing-key} 三選一。 */
    private String command;

    private Tenant tenant = new Tenant();
    private App app = new App();

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public App getApp() {
        return app;
    }

    public void setApp(App app) {
        this.app = app;
    }

    /** {@code create-tenant} 用租戶欄位 + {@code add-app-binding} 用來定位既有租戶的 {@code rp-id}。 */
    public static class Tenant {
        private String name;
        private String rpId;
        private String expectedOrigin;
        private int rateLimitTps = 100;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public int getRateLimitTps() {
            return rateLimitTps;
        }

        public void setRateLimitTps(int rateLimitTps) {
            this.rateLimitTps = rateLimitTps;
        }
    }

    /** {@code add-app-binding} 用 App 欄位。 */
    public static class App {
        private String packageName;
        private String sha256Fingerprint;
        private String label;

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getSha256Fingerprint() {
            return sha256Fingerprint;
        }

        public void setSha256Fingerprint(String sha256Fingerprint) {
            this.sha256Fingerprint = sha256Fingerprint;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
