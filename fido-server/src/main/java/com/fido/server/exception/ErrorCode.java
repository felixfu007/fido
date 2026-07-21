package com.fido.server.exception;

import org.springframework.http.HttpStatus;

/**
 * 對應 api-contract.md 1.4 通用錯誤碼表。
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    CHALLENGE_EXPIRED(HttpStatus.BAD_REQUEST),
    CHALLENGE_NOT_FOUND(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN),
    TENANT_DISABLED(HttpStatus.FORBIDDEN),
    RP_ID_MISMATCH(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CREDENTIAL_ALREADY_EXISTS(HttpStatus.CONFLICT),
    ATTESTATION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    ATTESTATION_CHAIN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    HARDWARE_SECURITY_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY),
    ASSERTION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    SIGN_COUNTER_REGRESSION(HttpStatus.UNPROCESSABLE_ENTITY),
    CREDENTIAL_REVOKED(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
