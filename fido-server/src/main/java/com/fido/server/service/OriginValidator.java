package com.fido.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【真實實作】比對 clientDataJSON.origin 是否為租戶允許的 origin
 * （db-schema.md `tenants.expected_origin`：可為單一字串或 JSON 陣列字串）。
 * 對應 api-contract.md「origin/RP ID hash 相符（不符 → 403 RP_ID_MISMATCH）」的
 * origin 比對部分（authenticatorData 內 rpIdHash 的比對則屬於尚未串接的介面卡範圍，
 * 見 webauthn 套件 Stub* 類別 Javadoc）。
 */
@Component
public class OriginValidator {

    private final ObjectMapper objectMapper;

    public OriginValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public boolean isAllowed(String origin, String expectedOriginRaw) {
        if (origin == null || expectedOriginRaw == null) {
            return false;
        }
        try {
            List<Object> list = objectMapper.readValue(expectedOriginRaw, List.class);
            return list.stream().anyMatch(o -> origin.equals(String.valueOf(o)));
        } catch (Exception e) {
            return origin.equals(expectedOriginRaw);
        }
    }
}
