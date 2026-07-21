package com.fido.server.webauthn;

/**
 * 解析後的 authenticatorData 結構（見 AuthenticatorDataParser）。
 *
 * @param aaguid                     僅 attestedCredentialDataIncluded 為真時有值（16 bytes）
 * @param credentialId               僅 attestedCredentialDataIncluded 為真時有值
 * @param credentialPublicKeyCose    僅 attestedCredentialDataIncluded 為真時有值；
 *                                   ED flag 情境下邊界未精確處理，見 AuthenticatorDataParser Javadoc
 */
public record AuthenticatorData(
        byte[] rpIdHash,
        byte flags,
        long signCount,
        boolean userPresent,
        boolean userVerified,
        boolean attestedCredentialDataIncluded,
        boolean extensionDataIncluded,
        byte[] aaguid,
        byte[] credentialId,
        byte[] credentialPublicKeyCose
) {
}
