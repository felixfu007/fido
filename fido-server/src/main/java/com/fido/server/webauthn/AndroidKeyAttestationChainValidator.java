package com.fido.server.webauthn;

/**
 * Android Key Attestation X.509 憑證鏈驗證介面（對齊 CLAUDE.md「驗證 Android Key
 * Attestation 憑證鏈」與「強制 TEE/StrongBox，不通過則拒絕註冊」）。
 *
 * <p><b>【介面卡 — 尚未實作真實驗證，僅骨架】</b>唯一實作為
 * {@link StubAndroidKeyAttestationChainValidator}，依設定檔固定回傳結果，
 * 不解析 X.509 憑證或 androidKeyAttestation extension（OID 1.3.6.1.4.1.11129.2.1.17）。
 * 正式版需要：
 * <ol>
 *   <li>從 attStmt.x5c 取出憑證鏈</li>
 *   <li>驗證鏈簽章至 Google 硬體 attestation root</li>
 *   <li>檢查憑證撤銷狀態（Google Key Attestation revocation list）</li>
 *   <li>解析葉憑證 androidKeyAttestation extension 取得 securityLevel /
 *       verifiedBootState 等資訊，換算為 {@link com.fido.server.domain.enums.SecurityLevel}</li>
 * </ol>
 * 建議導入 WebAuthn4J（其 android-key attestation statement 驗證器已涵蓋部分邏輯）取代本手刻介面。
 */
public interface AndroidKeyAttestationChainValidator {

    AttestationChainResult validate(ParsedAttestationObject attestationObject);
}
