package com.shop.reference.fidoclient.dto;

import java.util.List;

/** WebAuthn {@code PublicKeyCredentialDescriptor}：用於 excludeCredentials / allowCredentials。 */
public record CredentialDescriptor(String type, String id, List<String> transports) {
}
