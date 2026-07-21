package com.fido.server.webauthn;

/**
 * 解析後的 clientDataJSON 內容。
 *
 * @param type                webauthn.create / webauthn.get
 * @param challengeBase64Url  clientDataJSON 內的 challenge（base64url，未 decode）
 * @param origin              clientDataJSON 內的 origin
 */
public record ClientData(String type, String challengeBase64Url, String origin) {
}
