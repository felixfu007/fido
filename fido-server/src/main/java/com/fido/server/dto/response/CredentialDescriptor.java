package com.fido.server.dto.response;

import java.util.List;

/**
 * WebAuthn {@code PublicKeyCredentialDescriptor}，用於 excludeCredentials / allowCredentials。
 */
public record CredentialDescriptor(String type, String id, List<String> transports) {

    public static CredentialDescriptor publicKey(String idBase64Url, List<String> transports) {
        return new CredentialDescriptor("public-key", idBase64Url, transports);
    }
}
