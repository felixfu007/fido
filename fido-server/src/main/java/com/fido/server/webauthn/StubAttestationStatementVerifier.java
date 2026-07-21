package com.fido.server.webauthn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【介面卡 — 骨架 stub，非真實密碼學驗證，正式上線前必須替換】
 *
 * <p>永遠回傳 {@code true}（視為簽章驗證通過），僅記錄警告 log，不解析 x5c 憑證、
 * 不還原公鑰、不做任何簽章運算。
 */
@Component
public class StubAttestationStatementVerifier implements AttestationStatementVerifier {

    private static final Logger log = LoggerFactory.getLogger(StubAttestationStatementVerifier.class);

    @Override
    public boolean verify(ParsedAttestationObject attestationObject, byte[] clientDataHash) {
        log.warn("[STUB-NOT-REAL-CRYPTO] AttestationStatementVerifier 未實作真實簽章驗證，"
                + "一律回傳 true。fmt={}", attestationObject.fmt());
        return true;
    }
}
