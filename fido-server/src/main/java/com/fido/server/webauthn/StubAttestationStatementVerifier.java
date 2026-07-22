package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實密碼學驗證】
 *
 * <p>永遠回傳 {@code true}（視為簽章驗證通過），僅記錄警告 log，不解析 x5c 憑證、
 * 不還原公鑰、不做任何簽章運算。
 *
 * <p>僅在明確設定 {@code fido.attestation.mode=stub} 時才會啟用（供測試環境需要繞過真實
 * 密碼學驗證時使用）；預設（未設定或設為 {@code real}）一律使用
 * {@link RealAttestationStatementVerifier}，正式路徑不會走到本類別。
 */
@Component
@ConditionalOnProperty(prefix = "fido.attestation", name = "mode", havingValue = "stub")
public class StubAttestationStatementVerifier implements AttestationStatementVerifier {

    private static final Logger log = LoggerFactory.getLogger(StubAttestationStatementVerifier.class);

    @Override
    public boolean verify(ParsedAttestationObject attestationObject, byte[] clientDataHash) {
        log.warn("[STUB-NOT-REAL-CRYPTO] AttestationStatementVerifier 未實作真實簽章驗證，"
                + "一律回傳 true。fmt={}", attestationObject.fmt());
        return true;
    }
}
