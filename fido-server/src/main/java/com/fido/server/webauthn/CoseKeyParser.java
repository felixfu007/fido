package com.fido.server.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 【真實實作 — 盡力而為，附安全 fallback】解析 COSE_Key（CBOR，整數標籤）以取出
 * {@code alg}（COSE 演算法 ID，label 3）等中繼資料欄位。
 *
 * <p>只做欄位讀取，不涉及把 COSE_Key 還原為 {@code java.security.PublicKey} 或任何簽章驗證
 * （那部分屬於 {@link AssertionSignatureVerifier} 的介面卡骨架範圍）。CBOR 整數 map key
 * 經 Jackson 解為泛型 Map 時的鍵型別可能因函式庫版本而異，本類別以「找不到就回退預設值」
 * 的方式處理，避免因此拋出非預期例外中斷主流程。
 */
@Component
public class CoseKeyParser {

    private static final Logger log = LoggerFactory.getLogger(CoseKeyParser.class);

    /** COSE alg -7 = ES256，WebAuthn platform authenticator（含 Android Keystore）最常見演算法。 */
    public static final int DEFAULT_ALG_ES256 = -7;

    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    @SuppressWarnings("unchecked")
    public int extractAlg(byte[] coseKeyBytes) {
        try {
            Map<Object, Object> map = cborMapper.readValue(coseKeyBytes, Map.class);
            Object alg = map.get("3");
            if (alg == null) {
                alg = map.get(3);
            }
            if (alg instanceof Number number) {
                return number.intValue();
            }
            log.warn("COSE_Key 未找到可辨識的 alg 欄位，回退預設值 ES256(-7)");
        } catch (Exception e) {
            log.warn("COSE_Key 解析失敗，回退預設值 ES256(-7)：{}", e.getMessage());
        }
        return DEFAULT_ALG_ES256;
    }
}
