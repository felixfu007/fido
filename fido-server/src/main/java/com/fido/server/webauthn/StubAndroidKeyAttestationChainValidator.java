package com.fido.server.webauthn;

import com.fido.server.config.FidoProperties;
import com.fido.server.domain.enums.SecurityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實驗證】
 *
 * <p>不解析 X.509 憑證鏈或 androidKeyAttestation extension，直接依
 * {@code application.yml} 的 {@code fido.attestation.stub.*} 設定回傳固定結果，
 * 方便在不想組出真實憑證鏈 fixture 時，仍能測試「通過」與
 * 「HARDWARE_SECURITY_NOT_MET / ATTESTATION_CHAIN_INVALID」兩種分支（把
 * {@code default-security-level} 設為 SOFTWARE 或未知字串、或 {@code chain-valid} 設為
 * false 即可觸發對應失敗路徑）。
 *
 * <p>僅在明確設定 {@code fido.attestation.mode=stub} 時才會啟用；預設一律使用
 * {@link RealAndroidKeyAttestationChainValidator}（真實憑證鏈與 Google root 驗證），
 * 正式路徑不會走到本類別。
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "stub")
public class StubAndroidKeyAttestationChainValidator implements AndroidKeyAttestationChainValidator {

    private static final Logger log = LoggerFactory.getLogger(StubAndroidKeyAttestationChainValidator.class);

    private final FidoProperties properties;

    public StubAndroidKeyAttestationChainValidator(FidoProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttestationChainResult validate(ParsedAttestationObject attestationObject, byte[] expectedChallenge) {
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
