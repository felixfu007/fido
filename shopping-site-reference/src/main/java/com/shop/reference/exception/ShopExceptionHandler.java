package com.shop.reference.exception;

import com.shop.reference.authentication.jwt.JwtValidationException;
import com.shop.reference.crossdevice.CrossDevicePollSessionNotFoundException;
import com.shop.reference.fidoclient.FidoServerApiException;
import com.shop.reference.session.ExternalUserIdMismatchException;
import com.shop.reference.session.NotLoggedInException;
import com.shop.reference.session.StepUpRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 統一把「呼叫 fido-server 失敗」與「session JWT 驗證失敗」轉譯成購物網站自己的錯誤格式。
 *
 * <p>刻意把兩種例外分開處理並標註 {@code source}：{@code FIDO_SERVER} 代表 fido-server
 * 那端就已經拒絕這次請求（例如 challenge 過期、attestation 驗證失敗）；{@code SHOP_JWT_CHECK}
 * 代表 fido-server 端「宣稱」成功，但購物網站自己驗證 session JWT 沒有通過——這種情況在正常
 * 運作下不應該發生，一旦發生代表有異常（見
 * {@link com.shop.reference.authentication.jwt.FidoSessionJwtValidator} Javadoc），值得
 * 特別留意/告警，故意不與一般業務錯誤混在一起。
 */
@RestControllerAdvice
public class ShopExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopExceptionHandler.class);

    @ExceptionHandler(FidoServerApiException.class)
    public ResponseEntity<ShopErrorResponse> handleFidoServerApiException(FidoServerApiException ex) {
        log.warn("fido-server 回應錯誤：code={}, traceId={}, message={}", ex.getErrorCode(), ex.getTraceId(), ex.getMessage());
        Map<String, Object> details = new LinkedHashMap<>(ex.getDetails());
        if (ex.getTraceId() != null) {
            details.put("fidoServerTraceId", ex.getTraceId());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ShopErrorResponse.of("FIDO_SERVER", ex.getErrorCode(), ex.getMessage(), details));
    }

    @ExceptionHandler(NotLoggedInException.class)
    public ResponseEntity<ShopErrorResponse> handleNotLoggedIn(NotLoggedInException ex) {
        log.info("未登入呼叫被拒絕：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ShopErrorResponse.of("SHOP_SESSION", "NOT_LOGGED_IN", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(ExternalUserIdMismatchException.class)
    public ResponseEntity<ShopErrorResponse> handleExternalUserIdMismatch(ExternalUserIdMismatchException ex) {
        log.warn("externalUserId 與登入 session 不一致，拒絕請求（疑似 IDOR 嘗試）：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ShopErrorResponse.of("SHOP_SESSION", "EXTERNAL_USER_ID_MISMATCH", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<ShopErrorResponse> handleStepUpRequired(StepUpRequiredException ex) {
        log.info("跨裝置登入 session 嘗試執行敏感操作，要求 step-up：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ShopErrorResponse.of("SHOP_SESSION", "STEP_UP_REQUIRED", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(CrossDevicePollSessionNotFoundException.class)
    public ResponseEntity<ShopErrorResponse> handleCrossDevicePollSessionNotFound(CrossDevicePollSessionNotFoundException ex) {
        log.info("跨裝置登入 poll 請求缺少有效的 XDEV_POLL cookie：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ShopErrorResponse.of("SHOP_SESSION", "XDEV_POLL_SESSION_NOT_FOUND", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(JwtValidationException.class)
    public ResponseEntity<ShopErrorResponse> handleJwtValidationException(JwtValidationException ex) {
        log.error("Session JWT 驗證失敗（reasonCode={}）：{}", ex.getReasonCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ShopErrorResponse.of("SHOP_JWT_CHECK", ex.getReasonCode(), ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ShopErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ShopErrorResponse.of("SHOP_VALIDATION", "VALIDATION_ERROR", "Request validation failed.",
                        Map.of("fields", fieldErrors)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ShopErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception in shopping-site-reference", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ShopErrorResponse.of("SHOP_INTERNAL", "INTERNAL_ERROR", "Internal server error.", Map.of()));
    }
}
