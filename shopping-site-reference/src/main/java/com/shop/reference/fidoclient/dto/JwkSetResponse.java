package com.shop.reference.fidoclient.dto;

import java.util.List;

/** 對應 docs/api-contract.md 3.3 {@code GET /api/v1/.well-known/jwks.json} response body。 */
public record JwkSetResponse(List<Jwk> keys) {

    public record Jwk(String kty, String crv, String kid, String x, String y, String use, String alg) {
    }
}
