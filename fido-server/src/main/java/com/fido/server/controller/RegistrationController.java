package com.fido.server.controller;

import com.fido.server.context.TenantContext;
import com.fido.server.dto.request.RegistrationOptionsRequest;
import com.fido.server.dto.request.RegistrationResultRequest;
import com.fido.server.dto.response.RegistrationOptionsResponse;
import com.fido.server.dto.response.RegistrationResultResponse;
import com.fido.server.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 對應 api-contract.md 第 2 節：註冊流程 API。 */
@RestController
@RequestMapping("/api/v1/registration")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/options")
    public ResponseEntity<RegistrationOptionsResponse> options(@Valid @RequestBody RegistrationOptionsRequest request) {
        return ResponseEntity.ok(registrationService.createOptions(TenantContext.require(), request));
    }

    @PostMapping("/result")
    public ResponseEntity<RegistrationResultResponse> result(@Valid @RequestBody RegistrationResultRequest request) {
        RegistrationResultResponse response = registrationService.verifyResult(TenantContext.require(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
