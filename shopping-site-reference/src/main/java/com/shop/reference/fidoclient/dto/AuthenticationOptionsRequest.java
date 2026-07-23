package com.shop.reference.fidoclient.dto;

/**
 * 對應 docs/api-contract.md 3.1 request body。{@code externalUserId} 為選填：
 * 帶入則走已知使用者流程（{@code allowCredentials} 非空）；省略則走 discoverable /
 * usernameless 流程（由 Credential Manager 選帳號）。
 */
public record AuthenticationOptionsRequest(String externalUserId) {
}
