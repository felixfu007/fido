package com.fido.server.controller;

import com.fido.server.service.JwtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 對應 api-contract.md 3.3：公開端點（不需 API Key），見
 * {@code com.fido.server.filter.ApiKeyAuthFilter} 的 PUBLIC_PATH_PREFIXES 白名單。
 */
@RestController
public class JwksController {

    private final JwtService jwtService;

    public JwksController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/api/v1/.well-known/jwks.json")
    public JwtService.JwkSet jwks() {
        return jwtService.jwks();
    }
}
