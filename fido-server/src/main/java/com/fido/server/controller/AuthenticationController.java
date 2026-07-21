package com.fido.server.controller;

import com.fido.server.context.TenantContext;
import com.fido.server.dto.request.AuthenticationOptionsRequest;
import com.fido.server.dto.request.AuthenticationResultRequest;
import com.fido.server.dto.response.AuthenticationOptionsResponse;
import com.fido.server.dto.response.AuthenticationResultResponse;
import com.fido.server.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 對應 api-contract.md 第 3 節：登入流程 API。 */
@RestController
@RequestMapping("/api/v1/authentication")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/options")
    public ResponseEntity<AuthenticationOptionsResponse> options(
            @RequestBody(required = false) AuthenticationOptionsRequest request) {
        AuthenticationOptionsRequest effective = request != null ? request : new AuthenticationOptionsRequest(null);
        return ResponseEntity.ok(authenticationService.createOptions(TenantContext.require(), effective));
    }

    @PostMapping("/result")
    public ResponseEntity<AuthenticationResultResponse> result(@Valid @RequestBody AuthenticationResultRequest request) {
        return ResponseEntity.ok(authenticationService.verifyResult(TenantContext.require(), request));
    }
}
