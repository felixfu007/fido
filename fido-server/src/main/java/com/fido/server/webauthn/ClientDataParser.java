package com.fido.server.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * 【真實實作】解析 base64url 編碼的 clientDataJSON。
 *
 * <p>clientDataJSON 本身是純 JSON（非 CBOR），因此本解析器不涉及 attestation 相關密碼學，
 * 可直接、完整地實作，不屬於本骨架的「介面卡」範圍。
 */
@Component
public class ClientDataParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public ClientData parse(String clientDataJsonBase64Url) {
        byte[] json;
        try {
            json = Base64.getUrlDecoder().decode(clientDataJsonBase64Url);
        } catch (IllegalArgumentException e) {
            throw new WebAuthnValidationException("clientDataJSON 非合法 base64url");
        }
        Map<String, Object> map;
        try {
            map = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new WebAuthnValidationException("clientDataJSON 非合法 JSON：" + e.getMessage());
        }
        String type = (String) map.get("type");
        String challenge = (String) map.get("challenge");
        String origin = (String) map.get("origin");
        if (type == null || challenge == null || origin == null) {
            throw new WebAuthnValidationException("clientDataJSON 缺少 type/challenge/origin");
        }
        return new ClientData(type, challenge, origin);
    }

    public byte[] decodeRawJson(String clientDataJsonBase64Url) {
        return Base64.getUrlDecoder().decode(clientDataJsonBase64Url);
    }
}
