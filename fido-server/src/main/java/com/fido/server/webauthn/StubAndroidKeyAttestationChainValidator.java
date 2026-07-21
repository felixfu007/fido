package com.fido.server.webauthn;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.enums.SecurityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實驗證，正式上線前必須替換】
 *
 * <p>不解析 X.509 憑證鏈或 androidKeyAttestation extension，直接依
 * {@code application.yml} 的 {@code fido.attestation.stub.*} 設定回傳固定結果，
 * 方便 qa-engineer 測試「通過」與「HARDWARE_SECURITY_NOT_MET / ATTESTATION_CHAIN_INVALID」
 * 兩種分支（把 {@code default-security-level} 設為 SOFTWARE 或未知字串、或
 * {@code chain-valid} 設為 false 即可觸發對應失敗路徑）。
 */
@Component
public class StubAndroidKeyAttestationChainValidator implements AndroidKeyAttestationChainValidator {

    private static final Logger log = LoggerFactory.getLogger(StubAndroidKeyAttestationChainValidator.class);

    private final FidoProperties properties;

    public StubAndroidKeyAttestationChainValidator(FidoProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttestationChainResult validate(ParsedAttestationObject attestationObject) {
        boolean chainValid = properties.getAttestation().getStub().isChainValid();
        String rawLevel = properties.getAttestation().getStub().getDefaultSecurityLevel();
        SecurityLevel level;
        try {
            level = SecurityLevel.valueOf(rawLevel);
        } catch (IllegalArgumentException | NullPointerException e) {
            level = null; // 例如設定值為 SOFTWARE 或未知字串 -> 視為未達硬體安全區
        }
        log.warn("[STUB-NOT-REAL-CRYPTO] AndroidKeyAttestationChainValidator 未實作真實憑證鏈驗證，"
                + "回傳設定值 chainValid={}, securityLevel={}", chainValid, rawLevel);
        return new AttestationChainResult(chainValid, level, rawLevel);
    }
}
