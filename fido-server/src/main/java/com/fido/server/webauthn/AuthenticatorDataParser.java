package com.fido.server.webauthn;

import java.nio.ByteBuffer;

/**
 * 【真實實作】WebAuthn authenticatorData 原始位元組結構解析（固定版面，非 CBOR）。
 *
 * <p>格式：{@code rpIdHash(32) + flags(1) + signCount(4, big-endian uint32)
 * [+ attestedCredentialData: aaguid(16) + credIdLen(2) + credId(credIdLen) + credentialPublicKey(CBOR)]
 * [+ extensions(CBOR)]}
 *
 * <p>此解析不需 attestation 憑證鏈或簽章驗證，屬於可直接完整實作的部分，
 * 用於 rpIdHash 比對、UP/UV flag 判讀、sign counter 取值 — 這些是 registration/authentication
 * 流程中「語意正確」的骨架邏輯之一部分（見任務要求：challenge 時效／sign counter 檢查為
 * 必須做對的骨架行為，非密碼學驗證本身）。
 *
 * <p><b>已知限制（未實作）</b>：當 extensions flag（ED）為真時，credentialPublicKey 之後
 * 還有額外 CBOR extensions 資料；本解析器假設 ED=false，直接把 attestedCredentialData
 * 之後「剩餘全部 bytes」視為 credentialPublicKey（COSE_Key CBOR）。若 ED=true 會誤把
 * extensions 資料含入 credentialPublicKey。正式版建議改用 WebAuthn4J 等成熟函式庫处理
 * 這個邊界。
 */
public final class AuthenticatorDataParser {

    private AuthenticatorDataParser() {
    }

    private static final int RP_ID_HASH_LEN = 32;
    private static final int AAGUID_LEN = 16;

    public static final int FLAG_UP = 0x01;
    public static final int FLAG_UV = 0x04;
    public static final int FLAG_AT = 0x40;
    public static final int FLAG_ED = (byte) 0x80;

    public static AuthenticatorData parse(byte[] authData) {
        if (authData == null || authData.length < RP_ID_HASH_LEN + 1 + 4) {
            throw new WebAuthnValidationException("authenticatorData 長度不足");
        }
        ByteBuffer buf = ByteBuffer.wrap(authData);
        byte[] rpIdHash = new byte[RP_ID_HASH_LEN];
        buf.get(rpIdHash);
        byte flags = buf.get();
        long signCount = Integer.toUnsignedLong(buf.getInt());

        boolean up = (flags & FLAG_UP) != 0;
        boolean uv = (flags & FLAG_UV) != 0;
        boolean at = (flags & FLAG_AT) != 0;
        boolean ed = (flags & FLAG_ED) != 0;

        byte[] aaguid = null;
        byte[] credentialId = null;
        byte[] credentialPublicKeyCose = null;

        if (at) {
            if (buf.remaining() < AAGUID_LEN + 2) {
                throw new WebAuthnValidationException("authenticatorData 缺少 attestedCredentialData");
            }
            aaguid = new byte[AAGUID_LEN];
            buf.get(aaguid);
            int credIdLen = Short.toUnsignedInt(buf.getShort());
            if (buf.remaining() < credIdLen) {
                throw new WebAuthnValidationException("authenticatorData credentialId 長度不符");
            }
            credentialId = new byte[credIdLen];
            buf.get(credentialId);
            // 見類別 Javadoc「已知限制」：假設 ED=false，剩餘 bytes 全部視為 credentialPublicKey。
            credentialPublicKeyCose = new byte[buf.remaining()];
            buf.get(credentialPublicKeyCose);
        }

        return new AuthenticatorData(rpIdHash, flags, signCount, up, uv, at, ed, aaguid, credentialId, credentialPublicKeyCose);
    }
}
