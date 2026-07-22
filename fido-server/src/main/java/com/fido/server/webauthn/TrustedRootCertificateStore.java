package com.fido.server.webauthn;

import com.fido.server.config.FidoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Android Key Attestation 憑證鏈信任根集合。
 *
 * <p>正式部署路徑（{@link FidoProperties.Attestation.PocTrust#isEnabled()} 為預設值
 * {@code false} 時，即多數情況）行為與本類別新增 PoC 信任設定之前完全一致：只從 classpath
 * {@code android-attestation-roots/*.pem} 載入 Google 官方公開的 Android Hardware
 * Attestation root 憑證（見 {@code src/main/resources/android-attestation-roots/SOURCE.txt}
 * 記載的來源與擷取日期）。
 *
 * <p><b>PoC 專用擴充</b>（見 {@link FidoProperties.Attestation.PocTrust}）：僅當
 * {@code fido.attestation.poc-trust.enabled=true} 時，額外從
 * {@code fido.attestation.poc-trust.extra-roots-location} 指定的位置（預設
 * {@code file:./poc-trust-roots/*.pem}，一個 production classpath 之外的外部檔案路徑，
 * 避免 PoC 測試 root 被誤植入部署產物）載入額外的信任 root，用於 Android 模擬器等沒有真正
 * StrongBox/TEE、Key Attestation 憑證鏈鏈接到自產測試 root（而非 Google root）的開發情境。
 * 這組額外 root 是**用「加」的**，不會取代內建 Google root 信任集合。此開關與
 * {@code fido.attestation.mode}（控制要不要做真實密碼學驗證）完全獨立，不應混用。
 *
 * <p>測試環境可用 {@link #TrustedRootCertificateStore(List)} 建構子直接注入自簽測試 root，
 * 不需真的持有 Google 私鑰即可組出可驗證通過的測試憑證鏈（見
 * {@code RegistrationAndAuthenticationFlowTest} 的 {@code @TestConfiguration}）；這個建構子
 * 繞過本類別所有 PoC/classpath 載入邏輯，供既有測試沿用，不受本次擴充影響。
 */
@Component
public class TrustedRootCertificateStore {

    private static final Logger log = LoggerFactory.getLogger(TrustedRootCertificateStore.class);

    private static final String ROOT_CERTS_LOCATION_PATTERN = "classpath:android-attestation-roots/*.pem";

    private final List<X509Certificate> roots;

    @Autowired
    public TrustedRootCertificateStore(FidoProperties properties) {
        this(computeRoots(properties));
    }

    /** 供測試直接注入自訂信任集合，繞過 classpath / PoC 額外信任載入邏輯。 */
    public TrustedRootCertificateStore(List<X509Certificate> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalStateException("TrustedRootCertificateStore 至少需要一張受信任 root 憑證");
        }
        this.roots = List.copyOf(roots);
    }

    public List<X509Certificate> getRoots() {
        return roots;
    }

    private static List<X509Certificate> computeRoots(FidoProperties properties) {
        List<X509Certificate> merged = new ArrayList<>(loadFromLocation(ROOT_CERTS_LOCATION_PATTERN, true));

        FidoProperties.Attestation.PocTrust pocTrust = properties.getAttestation().getPocTrust();
        if (pocTrust.isEnabled()) {
            log.warn("【PoC 專用】fido.attestation.poc-trust.enabled=true —— 除了內建 Google 官方 root，"
                    + "本次啟動額外信任 {} 底下的測試 root。正式部署絕不應開啟此設定。",
                    pocTrust.getExtraRootsLocation());
            List<X509Certificate> extra = loadFromLocation(pocTrust.getExtraRootsLocation(), false);
            if (extra.isEmpty()) {
                log.warn("【PoC 專用】poc-trust.enabled=true 但 {} 底下找不到任何 PEM 憑證，"
                        + "本次仍只信任內建 Google root（可能尚未把模擬器/測試 root 放進該路徑）。",
                        pocTrust.getExtraRootsLocation());
            } else {
                log.warn("【PoC 專用】額外載入 {} 張測試 root 憑證（來源：{}）。",
                        extra.size(), pocTrust.getExtraRootsLocation());
            }
            // LinkedHashSet 去重（以編碼位元組比較），同時保留載入順序，避免同一張憑證重複計入。
            LinkedHashSet<X509Certificate> deduped = new LinkedHashSet<>(merged);
            deduped.addAll(extra);
            return List.copyOf(deduped);
        }

        return merged;
    }

    /**
     * @param required 找不到任何憑證時，{@code true} 視為致命設定錯誤（拋例外，用於內建
     *                 Google root——伺服器沒有它完全無法驗證任何 attestation）；
     *                 {@code false} 只記警告並回傳空清單（用於 PoC 額外 root——尚未準備好
     *                 測試憑證不該讓整台伺服器起不來）。
     */
    private static List<X509Certificate> loadFromLocation(String locationPattern, boolean required) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources;
            try {
                resources = resolver.getResources(locationPattern);
            } catch (FileNotFoundException e) {
                resources = new Resource[0];
            }
            List<X509Certificate> loaded = new ArrayList<>();
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    loaded.add((X509Certificate) certificateFactory.generateCertificate(in));
                }
            }
            if (loaded.isEmpty() && required) {
                throw new IllegalStateException(
                        "找不到內嵌的 Android Key Attestation root 憑證資源：" + locationPattern);
            }
            return loaded;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (required) {
                throw new IllegalStateException("載入 Android Key Attestation root 憑證失敗", e);
            }
            log.warn("【PoC 專用】載入額外信任 root（{}）時發生例外，本次略過：{}", locationPattern, e.getMessage());
            return List.of();
        }
    }
}
