package com.fido.server.webauthn;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Android Key Attestation 憑證鏈信任根集合。
 *
 * <p>預設（Spring 建立此元件時）從 classpath {@code android-attestation-roots/*.pem} 載入
 * Google 官方公開的 Android Hardware Attestation root 憑證（見
 * {@code src/main/resources/android-attestation-roots/SOURCE.txt} 記載的來源與擷取日期）。
 * 測試環境可用 {@link #TrustedRootCertificateStore(List)} 建構子注入自簽測試 root，
 * 不需真的持有 Google 私鑰即可組出可驗證通過的測試憑證鏈（見
 * {@code RegistrationAndAuthenticationFlowTest} 的 {@code @TestConfiguration}）。
 */
@Component
public class TrustedRootCertificateStore {

    private static final String ROOT_CERTS_LOCATION_PATTERN = "classpath:android-attestation-roots/*.pem";

    private final List<X509Certificate> roots;

    public TrustedRootCertificateStore() {
        this(loadFromClasspath());
    }

    public TrustedRootCertificateStore(List<X509Certificate> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalStateException("TrustedRootCertificateStore 至少需要一張受信任 root 憑證");
        }
        this.roots = List.copyOf(roots);
    }

    public List<X509Certificate> getRoots() {
        return roots;
    }

    private static List<X509Certificate> loadFromClasspath() {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(ROOT_CERTS_LOCATION_PATTERN);
            List<X509Certificate> loaded = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    loaded.add((X509Certificate) certificateFactory.generateCertificate(in));
                }
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException(
                        "找不到內嵌的 Android Key Attestation root 憑證資源：" + ROOT_CERTS_LOCATION_PATTERN);
            }
            return loaded;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("載入 Android Key Attestation root 憑證失敗", e);
        }
    }
}
