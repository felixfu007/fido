package com.fido.server.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 【真實實作】API Key 雜湊工具，對應 db-schema.md DB2：API Key 只存 SHA-256 雜湊 +
 * 明文前綴，不存明文。
 */
@Service
public class ApiKeyService {

    public byte[] hash(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String prefix(String rawApiKey) {
        return rawApiKey.length() <= 12 ? rawApiKey : rawApiKey.substring(0, 12);
    }
}
