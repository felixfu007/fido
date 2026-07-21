package com.fido.server.webauthn;

import com.fido.server.domain.enums.SecurityLevel;

/**
 * Android Key Attestation 憑證鏈驗證結果。
 *
 * @param chainValid       憑證鏈是否驗證通過（含撤銷檢查）
 * @param securityLevel    偵測到的硬體安全等級；null 表示偵測結果為 SOFTWARE 或無法判定
 *                         （對應 422 HARDWARE_SECURITY_NOT_MET）
 * @param detectedLevelRaw 偵測到的原始等級字串，供錯誤 response 的 details 與 audit_log 使用
 */
public record AttestationChainResult(boolean chainValid, SecurityLevel securityLevel, String detectedLevelRaw) {
}
