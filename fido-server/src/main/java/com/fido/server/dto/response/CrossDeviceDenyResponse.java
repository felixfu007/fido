package com.fido.server.dto.response;

/**
 * 對應 api-contract.md §3.4.E response 200 body。{@code status} 恆為 {@code "DENIED"}
 * （含冪等路徑：已是 {@code DENIED} 的 session 再次呼叫仍回同樣的 200 body）。
 */
public record CrossDeviceDenyResponse(String status) {
}
