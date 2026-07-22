package com.fido.server.webauthn;

/**
 * Android Key Attestation X.509 憑證鏈驗證介面（對齊 CLAUDE.md「驗證 Android Key
 * Attestation 憑證鏈」與「強制 TEE/StrongBox，不通過則拒絕註冊」）。
 *
 * <p>正式路徑（{@code fido.attestation.mode=real}，預設）實作為
 * {@link RealAndroidKeyAttestationChainValidator}：
 * <ol>
 *   <li>從 attStmt.x5c 取出憑證鏈，驗證逐張簽章與效期</li>
 *   <li>驗證鏈尾公鑰等於內建 Google 官方 Hardware Attestation root 公鑰之一
 *       （見 {@link TrustedRootCertificateStore}）</li>
 *   <li>解析 leaf 憑證 androidKeyAttestation extension（OID
 *       1.3.6.1.4.1.11129.2.1.17）取得 attestationSecurityLevel /
 *       keymasterSecurityLevel，換算為 {@link com.fido.server.domain.enums.SecurityLevel}</li>
 * </ol>
 * <p>{@code expectedChallenge} 為本次註冊 ceremony 核發的 challenge bytes（與
 * {@code PublicKeyCredentialCreationOptions.challenge} 同一份），用來與 leaf 憑證 Key
 * Attestation extension 內的 {@code attestationChallenge} 欄位比對，防止重放其他 ceremony
 * 產生的合法憑證鏈。
 *
 * <p><b>尚未涵蓋</b>（見 dev-engineer 回報的開放問題）：Google 線上憑證撤銷清單查詢。
 * 測試環境可設 {@code fido.attestation.mode=stub} 切回
 * {@link StubAndroidKeyAttestationChainValidator}（依設定檔固定回傳結果）。
 */
public interface AndroidKeyAttestationChainValidator {

    AttestationChainResult validate(ParsedAttestationObject attestationObject, byte[] expectedChallenge);
}
