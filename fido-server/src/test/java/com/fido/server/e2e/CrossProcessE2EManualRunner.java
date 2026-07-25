package com.fido.server.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fido.server.testsupport.TestKeyAttestationFixtures;
import com.fido.server.testsupport.WebAuthnCeremonyFixtures;

import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 【手動 QA 驗證用工具，非自動化測試套件的一部分】
 *
 * <p>真正啟動兩個獨立 JVM 行程（fido-server + shopping-site-reference），透過真實 HTTP
 * 呼叫（非 MockMvc / @SpringBootTest 同行程）跑一次完整的註冊 -&gt; 登入 -&gt; JWT 驗證 ->
 * 裝置管理 -&gt; IDOR 反例流程，驗證兩個服務在真實跨行程邊界下的實際行為，補上兩邊各自
 * mock/in-process 測試套件都沒有涵蓋到的網路/序列化/JWKS 邊界。
 *
 * <p>用法（於 repo 根目錄 D:\fido 執行，事前需已 `mvn -DskipTests package` 建置好兩邊的
 * jar，且已 `mvn test-compile` 讓本類別與測試 fixture 一起編譯）：見
 * scratchpad 內的 run 腳本，或手動組出 classpath 後執行
 * {@code java -cp <classpath> com.fido.server.e2e.CrossProcessE2EManualRunner}。
 *
 * <p>不會被一般 `mvn test`（surefire 預設 include pattern）撿到執行，因為類別名稱不符合
 * {@code *Test}/{@code Test*}/{@code *Tests}/{@code *TestCase} 慣例；純粹透過本類別的
 * {@code main} 方法手動觸發。
 */
public final class CrossProcessE2EManualRunner {

    private static final String FIDO_BASE_URL = "http://localhost:8443";
    // 8081 在本機開發環境上已被另一個無關服務（macmnsvc.exe）長期佔用（LISTENING），
    // 改用 18081 避免撞埠；shopping-site-reference 的 fido-client.base-url 預設指向
    // fido-server 的 8443，與此埠無關，不受影響。
    private static final String SHOP_BASE_URL = "http://localhost:18081";
    private static final String RP_ID = "shop.example.com";
    private static final String ORIGIN = "https://shop.example.com";

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    private static final List<String> RESULTS = new ArrayList<>();
    private static boolean anyFailure = false;
    private static boolean criticalFailure = false;

    public static void main(String[] args) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        System.out.println("[harness] repo root resolved as: " + repoRoot);

        Path fidoDir = repoRoot.resolve("fido-server");
        Path shopDir = repoRoot.resolve("shopping-site-reference");
        Path fidoJar = fidoDir.resolve("target/fido-server-1.0.0.jar");
        Path shopJar = shopDir.resolve("target/shopping-site-reference-1.0.0.jar");
        if (!Files.exists(fidoJar) || !Files.exists(shopJar)) {
            throw new IllegalStateException("找不到已建置的 jar，請先在兩個模組各自執行 `mvn -DskipTests package`。"
                    + " fidoJar=" + fidoJar + " (exists=" + Files.exists(fidoJar) + ")"
                    + " shopJar=" + shopJar + " (exists=" + Files.exists(shopJar) + ")");
        }

        // ---- 準備 PoC 專用測試 root，讓真實啟動的 fido-server 行程能信任
        //      TestKeyAttestationFixtures 自簽的 Android Key Attestation 憑證鏈 ----
        Path pocTrustDir = fidoDir.resolve("poc-trust-roots");
        Files.createDirectories(pocTrustDir);
        Path rootPemPath = pocTrustDir.resolve("e2e-manual-run-root.pem");
        writePem(rootPemPath, TestKeyAttestationFixtures.ROOT_CERTIFICATE.getEncoded());
        System.out.println("[harness] wrote PoC-trust test root to: " + rootPemPath);

        Path logDir = Path.of(System.getProperty("java.io.tmpdir"), "fido-e2e-logs");
        Files.createDirectories(logDir);

        Process fidoProcess = null;
        Process shopProcess = null;
        try {
            fidoProcess = startProcess("fido-server", fidoDir,
                    List.of("java", "-jar", fidoJar.toString(),
                            "--server.port=8443",
                            "--fido.persistence.mode=memory",
                            "--fido.attestation.poc-trust.enabled=true",
                            // fido.dev-seed.enabled 的正式建置預設值已改為 false（見 CLAUDE.md
                            // 「租戶開通 / 簽章金鑰 CLI 決策」段落），這個 harness 靠 dev-seed 建立
                            // shopping-site-reference 用來打 API 的示範租戶（固定 API Key，見
                            // shopping-site-reference/src/main/resources/application.yml），
                            // 必須明確 opt-in，否則對 fido-server 的每個代理呼叫都會因租戶不存在
                            // 收到 401，而非依賴已改掉的舊預設值。
                            "--fido.dev-seed.enabled=true"),
                    logDir.resolve("fido-server.log"));

            waitForHttp(FIDO_BASE_URL + "/actuator/health", "fido-server", 60);
            record("fido-server 行程真的啟動並可透過 HTTP 存活探測（/actuator/health）", true, false);

            shopProcess = startProcess("shopping-site-reference", shopDir,
                    List.of("java", "-jar", shopJar.toString(),
                            "--server.port=18081",
                            // shop.session.cookie.secure 預設 true（見 CLAUDE.md 待辦事項「session
                            // cookie secure 預設值」修復後的 application.yml），但本 harness 是以
                            // 純 http://localhost 跑兩個行程（無 TLS 反向代理）；Secure cookie 在
                            // http 連線下不會被送出，會讓 SHOP_SESSION 登入 session 與新加入的
                            // XSRF-TOKEN CSRF double-submit cookie 都失效。用內建的
                            // application-local-http.yml profile 明確 opt-out，而非更動預設值。
                            "--spring.profiles.active=local-http"),
                    logDir.resolve("shopping-site-reference.log"));

            waitForHttp(SHOP_BASE_URL + "/shop/api/session/whoami", "shopping-site-reference", 60);
            record("shopping-site-reference 行程真的啟動並可透過 HTTP 存活探測（/shop/api/session/whoami）", true, false);

            runFlows();
        } catch (Exception e) {
            criticalFailure = true;
            anyFailure = true;
            System.out.println("[harness] 執行過程發生未預期例外，中止：" + e);
            e.printStackTrace(System.out);
        } finally {
            System.out.println();
            System.out.println("======= fido-server 行程日誌節錄（最後 60 行，見 " + logDir.resolve("fido-server.log") + "） =======");
            tailLog(logDir.resolve("fido-server.log"), 60);
            System.out.println();
            System.out.println("======= shopping-site-reference 行程日誌節錄（最後 60 行，見 " + logDir.resolve("shopping-site-reference.log") + "） =======");
            tailLog(logDir.resolve("shopping-site-reference.log"), 60);

            if (shopProcess != null) {
                shopProcess.destroy();
                shopProcess.waitFor(10, TimeUnit.SECONDS);
                shopProcess.destroyForcibly();
            }
            if (fidoProcess != null) {
                fidoProcess.destroy();
                fidoProcess.waitFor(10, TimeUnit.SECONDS);
                fidoProcess.destroyForcibly();
            }
            System.out.println();
            System.out.println("[harness] 兩個行程皆已停止。");
        }

        System.out.println();
        System.out.println("=========================== 結果總表 ===========================");
        for (String line : RESULTS) {
            System.out.println(line);
        }
        System.out.println("==================================================================");
        if (anyFailure) {
            System.out.println("整體結論：至少一項驗證失敗" + (criticalFailure ? "（含關鍵/阻斷性失敗，流程已中止）" : "") + "。");
            System.exit(1);
        } else {
            System.out.println("整體結論：全部驗證項目通過。");
        }
    }

    private static void runFlows() throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // ---- Step 0：以一次 GET 觸發 shopping-site-reference 的 CsrfCookieFilter 核發
        //      XSRF-TOKEN cookie（double-submit CSRF 防護，見 com.shop.reference.security.
        //      CsrfCookieFilter）。真實前端是靠頁面載入時的 GET 請求（例如 /shop/api/session/
        //      whoami、或直接載入 index.html）取得這個 cookie，之後所有 POST/DELETE 才讀
        //      document.cookie 回填進 X-XSRF-TOKEN header；這裡用同一支 whoami 端點模擬。 ----
        HttpResponse<String> csrfBootstrapResp = getJson(http, SHOP_BASE_URL + "/shop/api/session/whoami", Map.of());
        record("啟動階段以 GET /shop/api/session/whoami 觸發 CsrfCookieFilter 核發 XSRF-TOKEN cookie"
                        + "（double-submit CSRF 防護的前置步驟，本 harness 用來模擬真實前端頁面載入時的行為）",
                csrfBootstrapResp.statusCode() == 200, true, csrfBootstrapResp.statusCode(), csrfBootstrapResp.body());
        if (csrfBootstrapResp.statusCode() != 200) {
            return;
        }
        String csrfToken = extractCookieValue(cookieManager, "XSRF-TOKEN");
        record("成功從 Set-Cookie 取得 XSRF-TOKEN 值，後續所有狀態變更請求（POST/DELETE）皆會夾帶 X-XSRF-TOKEN header",
                csrfToken != null && !csrfToken.isBlank(), true);
        if (csrfToken == null || csrfToken.isBlank()) {
            return;
        }
        Map<String, String> csrfHeader = Map.of("X-XSRF-TOKEN", csrfToken);

        String externalUserId = "u-e2e-" + RANDOM.nextInt(1_000_000);
        System.out.println("[harness] externalUserId = " + externalUserId);

        // ---- Step: demo login（建立購物網站 session，發起註冊的前提） ----
        HttpResponse<String> loginResp = postJson(http, SHOP_BASE_URL + "/shop/api/session/login-as",
                Map.of("externalUserId", externalUserId), csrfHeader);
        record("Demo login-as 建立 SHOP_SESSION（POST /shop/api/session/login-as）",
                loginResp.statusCode() == 200, false, loginResp.statusCode(), loginResp.body());
        if (loginResp.statusCode() != 200) {
            return;
        }

        // ---- Step 3: 註冊 ceremony（透過 shopping-site-reference 代理） ----
        HttpResponse<String> regOptionsResp = postJson(http, SHOP_BASE_URL + "/shop/api/fido/registration/options",
                Map.of("externalUserId", externalUserId, "displayName", "E2E Test User", "deviceLabel", "E2E Test Device"),
                csrfHeader);
        boolean regOptionsOk = regOptionsResp.statusCode() == 200;
        record("註冊 options：透過 shopping-site-reference 代理向 fido-server 取得 challenge（POST /shop/api/fido/registration/options）",
                regOptionsOk, true, regOptionsResp.statusCode(), regOptionsResp.body());
        if (!regOptionsOk) {
            criticalFailure = true;
            return;
        }

        JsonNode regOptionsJson = JSON.readTree(regOptionsResp.body());
        String regCeremonyId = regOptionsJson.get("ceremonyId").asText();
        String regChallengeB64 = regOptionsJson.get("publicKey").get("challenge").asText();

        KeyPair credentialKeyPair = WebAuthnCeremonyFixtures.generateEcKeyPair();
        byte[] credentialId = WebAuthnCeremonyFixtures.randomBytes(32);
        byte[] coseKeyBytes = WebAuthnCeremonyFixtures.buildEcCoseKeyBytes(CBOR, (ECPublicKey) credentialKeyPair.getPublic());
        byte[] authenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(RP_ID, (byte) 0x41 /* UP+AT */, 0L,
                new byte[16], credentialId, coseKeyBytes);
        byte[] clientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(JSON, "webauthn.create", regChallengeB64, ORIGIN);
        byte[] clientDataHash = WebAuthnCeremonyFixtures.sha256(clientDataJson);

        byte[] challengeBytes = B64URL_DEC.decode(regChallengeB64);
        var leafCert = TestKeyAttestationFixtures.buildLeafCertificate(
                credentialKeyPair.getPublic(), TestKeyAttestationFixtures.SECURITY_LEVEL_STRONG_BOX, challengeBytes);
        byte[] attStmtSig = WebAuthnCeremonyFixtures.signEcdsa(credentialKeyPair.getPrivate(), authenticatorData, clientDataHash);
        byte[] attestationObject = WebAuthnCeremonyFixtures.buildAttestationObject(CBOR, "android-key", authenticatorData, attStmtSig, leafCert);

        Map<String, Object> credential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(clientDataJson),
                        "attestationObject", B64URL.encodeToString(attestationObject),
                        "transports", List.of("internal")));

        Map<String, Object> regResultBody = new LinkedHashMap<>();
        regResultBody.put("ceremonyId", regCeremonyId);
        regResultBody.put("externalUserId", externalUserId);
        regResultBody.put("credential", credential);
        regResultBody.put("deviceLabel", "E2E Test Device");

        HttpResponse<String> regResultResp = postJson(http, SHOP_BASE_URL + "/shop/api/fido/registration/result",
                regResultBody, csrfHeader);
        boolean regResultOk = regResultResp.statusCode() == 201;
        record("註冊 result：真實 android-key attestation（真實 StrongBox 等級聲明 + 密碼學合法憑證鏈+簽章）送驗，"
                        + "經 shopping-site-reference 代理到 fido-server 真實驗證通過並建立憑證/裝置（POST /shop/api/fido/registration/result -> 201）",
                regResultOk, true, regResultResp.statusCode(), regResultResp.body());
        if (!regResultOk) {
            criticalFailure = true;
            return;
        }
        JsonNode regResultJson = JSON.readTree(regResultResp.body());
        String deviceId = regResultJson.get("deviceId").asText();

        // ---- 用裝置列表端點（而非只看 200）確認憑證/裝置真的落庫 ----
        HttpResponse<String> deviceListAfterRegResp = getJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices?status=ACTIVE", Map.of());
        JsonNode deviceListAfterReg = deviceListAfterRegResp.statusCode() == 200 ? JSON.readTree(deviceListAfterRegResp.body()) : null;
        boolean deviceListedAfterReg = deviceListAfterReg != null && deviceListAfterReg.get("devices").isArray()
                && anyDeviceIdMatches(deviceListAfterReg.get("devices"), deviceId, "ACTIVE");
        record("裝置列表確認註冊真的落庫（非僅看 200 狀態碼）：GET /shop/api/fido/devices?status=ACTIVE 內含 deviceId="
                        + deviceId + " 且 status=ACTIVE",
                deviceListedAfterReg, true, deviceListAfterRegResp.statusCode(), deviceListAfterRegResp.body());
        if (!deviceListedAfterReg) {
            criticalFailure = true;
            return;
        }

        // ---- Step 4: 登入 ceremony ----
        HttpResponse<String> authOptionsResp = postJson(http, SHOP_BASE_URL + "/shop/api/fido/authentication/options",
                Map.of("externalUserId", externalUserId), csrfHeader);
        boolean authOptionsOk = authOptionsResp.statusCode() == 200;
        record("登入 options（POST /shop/api/fido/authentication/options）", authOptionsOk, true,
                authOptionsResp.statusCode(), authOptionsResp.body());
        if (!authOptionsOk) {
            criticalFailure = true;
            return;
        }
        JsonNode authOptionsJson = JSON.readTree(authOptionsResp.body());
        String authCeremonyId = authOptionsJson.get("ceremonyId").asText();
        String authChallengeB64 = authOptionsJson.get("publicKey").get("challenge").asText();

        byte[] assertionAuthenticatorData = WebAuthnCeremonyFixtures.buildAuthenticatorData(RP_ID, (byte) 0x05 /* UP+UV */, 1L,
                null, null, null);
        byte[] assertionClientDataJson = WebAuthnCeremonyFixtures.buildClientDataJson(JSON, "webauthn.get", authChallengeB64, ORIGIN);
        byte[] assertionSignature = WebAuthnCeremonyFixtures.signEcdsa(credentialKeyPair.getPrivate(),
                assertionAuthenticatorData, WebAuthnCeremonyFixtures.sha256(assertionClientDataJson));

        Map<String, Object> assertionCredential = Map.of(
                "id", B64URL.encodeToString(credentialId),
                "rawId", B64URL.encodeToString(credentialId),
                "type", "public-key",
                "response", Map.of(
                        "clientDataJSON", B64URL.encodeToString(assertionClientDataJson),
                        "authenticatorData", B64URL.encodeToString(assertionAuthenticatorData),
                        "signature", B64URL.encodeToString(assertionSignature)));

        Map<String, Object> authResultBody = Map.of("ceremonyId", authCeremonyId, "credential", assertionCredential);

        HttpResponse<String> authResultResp = postJson(http, SHOP_BASE_URL + "/shop/api/fido/authentication/result",
                authResultBody, csrfHeader);
        boolean authResultOk = authResultResp.statusCode() == 200;
        record("登入 result：真實 assertion 簽章送驗，shopping-site-reference 代理 -> fido-server 真實驗證 -> 簽發 session JWT "
                        + "-> shopping-site-reference 真的透過 HTTP 打 fido-server JWKS 端點、ES256 驗簽通過、建立自己的 SHOP_SESSION "
                        + "(POST /shop/api/fido/authentication/result -> 200)",
                authResultOk, true, authResultResp.statusCode(), authResultResp.body());
        if (!authResultOk) {
            criticalFailure = true;
            return;
        }
        JsonNode authResultJson = JSON.readTree(authResultResp.body());
        boolean loginFlagTrue = authResultJson.has("loggedIn") && authResultJson.get("loggedIn").asBoolean(false);
        record("登入 result body 回報 loggedIn=true（ShopLoginResponse，代表 shopping-site-reference 獨立完成 JWT 驗證後才建立 session，"
                        + "不是單看 fido-server 回應的 verified 欄位）",
                loginFlagTrue, false, authResultResp.statusCode(), authResultResp.body());

        // ---- Step 6: 用新登入建立的 SHOP_SESSION 呼叫裝置列表/撤銷代理 ----
        HttpResponse<String> deviceListAfterLoginResp = getJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices?status=ACTIVE", Map.of());
        boolean deviceListAfterLoginOk = deviceListAfterLoginResp.statusCode() == 200;
        JsonNode deviceListAfterLoginJson = deviceListAfterLoginOk ? JSON.readTree(deviceListAfterLoginResp.body()) : null;
        boolean deviceListAfterLoginHasDevice = deviceListAfterLoginJson != null
                && anyDeviceIdMatches(deviceListAfterLoginJson.get("devices"), deviceId, "ACTIVE");
        record("用 FIDO 登入後新建立的 SHOP_SESSION 呼叫裝置列表代理，確實透過 fido-server 真實查得同一台裝置"
                        + "（GET /shop/api/fido/devices?status=ACTIVE）",
                deviceListAfterLoginHasDevice, false, deviceListAfterLoginResp.statusCode(), deviceListAfterLoginResp.body());

        // ---- Step 7: 反例 —— externalUserId 與 session 不一致，應真實回 403（IDOR 修復的線上驗證） ----
        HttpResponse<String> idorListResp = getJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices?status=ACTIVE&externalUserId=some-other-victim-user", Map.of());
        JsonNode idorListJson = idorListResp.body() != null && !idorListResp.body().isBlank()
                ? tryParseJson(idorListResp.body()) : null;
        boolean idorListRejected = idorListResp.statusCode() == 403
                && idorListJson != null && idorListJson.has("error")
                && "EXTERNAL_USER_ID_MISMATCH".equals(idorListJson.get("error").path("code").asText(null));
        record("IDOR 反例 #1（列表）：帶入與登入 session 不一致的 externalUserId query 參數，"
                        + "真實跨行程呼叫應被拒絕 403 EXTERNAL_USER_ID_MISMATCH（GET /shop/api/fido/devices?externalUserId=<spoofed>）",
                idorListRejected, true, idorListResp.statusCode(), idorListResp.body());

        HttpResponse<String> idorRevokeResp = deleteJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices/" + deviceId + "?externalUserId=some-other-victim-user", csrfHeader);
        JsonNode idorRevokeJson = idorRevokeResp.body() != null && !idorRevokeResp.body().isBlank()
                ? tryParseJson(idorRevokeResp.body()) : null;
        boolean idorRevokeRejected = idorRevokeResp.statusCode() == 403
                && idorRevokeJson != null && idorRevokeJson.has("error")
                && "EXTERNAL_USER_ID_MISMATCH".equals(idorRevokeJson.get("error").path("code").asText(null));
        record("IDOR 反例 #2（撤銷）：帶入與登入 session 不一致的 externalUserId query 參數，"
                        + "真實跨行程呼叫應被拒絕 403 EXTERNAL_USER_ID_MISMATCH（DELETE /shop/api/fido/devices/{id}?externalUserId=<spoofed>）",
                idorRevokeRejected, true, idorRevokeResp.statusCode(), idorRevokeResp.body());

        // ---- 正常撤銷：確認裝置管理代理在合法路徑下也真的把撤銷動作轉發到 fido-server ----
        HttpResponse<String> legitRevokeResp = deleteJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices/" + deviceId, csrfHeader);
        boolean legitRevokeOk = legitRevokeResp.statusCode() == 200;
        JsonNode legitRevokeJson = legitRevokeOk ? JSON.readTree(legitRevokeResp.body()) : null;
        boolean legitRevokeStatusRevoked = legitRevokeJson != null && "REVOKED".equals(legitRevokeJson.path("status").asText(null));
        record("合法撤銷：用真正登入者自己的 session 撤銷自己的裝置，代理應成功轉發並回真實撤銷結果"
                        + "（DELETE /shop/api/fido/devices/{id} -> 200 status=REVOKED）",
                legitRevokeOk && legitRevokeStatusRevoked, false, legitRevokeResp.statusCode(), legitRevokeResp.body());

        HttpResponse<String> deviceListAfterRevokeResp = getJson(http,
                SHOP_BASE_URL + "/shop/api/fido/devices?status=ALL", Map.of());
        JsonNode deviceListAfterRevokeJson = deviceListAfterRevokeResp.statusCode() == 200
                ? JSON.readTree(deviceListAfterRevokeResp.body()) : null;
        boolean revokedVisibleInAll = deviceListAfterRevokeJson != null
                && anyDeviceIdMatches(deviceListAfterRevokeJson.get("devices"), deviceId, "REVOKED");
        record("撤銷後 status=ALL 裝置列表可見該裝置狀態變為 REVOKED（跨行程真實查詢，非快取值）",
                revokedVisibleInAll, false, deviceListAfterRevokeResp.statusCode(), deviceListAfterRevokeResp.body());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * 從 {@link CookieManager} 的 cookie jar 讀出指定名稱 cookie 的值——double-submit CSRF
     * 防護需要把 cookie 的實際內容回填進 header，光靠 {@link CookieManager} 自動重送 cookie
     * 是不夠的（見 com.shop.reference.security.CsrfCookieFilter Javadoc 的攻擊原理說明）。
     */
    private static String extractCookieValue(CookieManager cookieManager, String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(c -> name.equals(c.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private static boolean anyDeviceIdMatches(JsonNode devices, String deviceId, String expectedStatus) {
        if (devices == null || !devices.isArray()) {
            return false;
        }
        for (JsonNode d : devices) {
            if (deviceId.equals(d.path("deviceId").asText(null)) && expectedStatus.equals(d.path("status").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode tryParseJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static void record(String description, boolean pass, boolean critical) {
        record(description, pass, critical, null, null);
    }

    private static void record(String description, boolean pass, boolean critical, Integer status, String body) {
        String marker = pass ? "PASS" : (critical ? "FAIL(CRITICAL)" : "FAIL");
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(marker).append("] ").append(description);
        if (status != null) {
            sb.append(" | HTTP ").append(status);
        }
        if (body != null) {
            String trimmed = body.length() > 500 ? body.substring(0, 500) + "...(truncated)" : body;
            sb.append(" | body=").append(trimmed);
        }
        String line = sb.toString();
        RESULTS.add(line);
        System.out.println(line);
        if (!pass) {
            anyFailure = true;
            if (critical) {
                criticalFailure = true;
            }
        }
    }

    private static HttpResponse<String> postJson(HttpClient http, String url, Map<String, Object> body,
                                                   Map<String, String> extraHeaders) throws IOException, InterruptedException {
        String json = JSON.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        extraHeaders.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getJson(HttpClient http, String url, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        extraHeaders.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> deleteJson(HttpClient http, String url, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .DELETE();
        extraHeaders.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Process startProcess(String label, Path workDir, List<String> command, Path logFile) throws IOException {
        System.out.println("[harness] starting " + label + " in " + workDir + " -> " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        return pb.start();
    }

    private static void waitForHttp(String url, String label, int timeoutSeconds) throws InterruptedException {
        HttpClient probe = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> resp = probe.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 500) {
                    System.out.println("[harness] " + label + " is up (HTTP " + resp.statusCode() + " on " + url + ")");
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        throw new IllegalStateException(label + " 在 " + timeoutSeconds + " 秒內未能就緒（" + url + "）", last);
    }

    private static void writePem(Path path, byte[] derEncoded) throws IOException {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(derEncoded);
        String pem = "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
        Files.writeString(path, pem, StandardCharsets.UTF_8);
    }

    private static void tailLog(Path logFile, int lines) {
        try {
            if (!Files.exists(logFile)) {
                System.out.println("(log file not found: " + logFile + ")");
                return;
            }
            List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            for (int i = from; i < all.size(); i++) {
                System.out.println(all.get(i));
            }
        } catch (IOException e) {
            System.out.println("(failed to read log file " + logFile + ": " + e + ")");
        }
    }

    private CrossProcessE2EManualRunner() {
    }
}
