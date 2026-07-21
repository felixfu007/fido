package com.fido.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 【真實實作】{@code fido_credentials.transports}（db-schema.md：JSON 陣列字串）
 * 與 {@code List<String>} 之間的轉換。
 */
@Component
public class TransportsCodec {

    private final ObjectMapper objectMapper;

    public TransportsCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(List<String> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(transports);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> decode(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
