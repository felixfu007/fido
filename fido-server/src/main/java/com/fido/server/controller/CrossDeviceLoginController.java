package com.fido.server.controller;

import com.fido.server.context.TenantContext;
import com.fido.server.dto.request.CrossDeviceDenyRequest;
import com.fido.server.dto.request.CrossDeviceResultRequest;
import com.fido.server.dto.request.CrossDeviceSessionCreateRequest;
import com.fido.server.dto.response.CrossDeviceClaimResponse;
import com.fido.server.dto.response.CrossDeviceDenyResponse;
import com.fido.server.dto.response.CrossDeviceResultResponse;
import com.fido.server.dto.response.CrossDeviceSessionCreateResponse;
import com.fido.server.dto.response.CrossDeviceStatusResponse;
import com.fido.server.service.CrossDeviceLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 對應 api-contract.md §3.4：跨裝置 QR 登入（情境三）四個端點。
 *
 * <p>端點 A（{@link #create}）、D（{@link #status}）呼叫方為購物網站後端，帶 {@code X-API-Key}，
 * 走一般 {@code ApiKeyAuthFilter} 認證流程，{@link TenantContext} 已設定。
 *
 * <p>端點 B（{@link #claim}）、C（{@link #result}）、E（{@link #deny}）呼叫方為手機 App 直連，
 * **不帶** {@code X-API-Key}（api-contract.md §1.2.2 / D16），已在 {@code ApiKeyAuthFilter} 的
 * {@code shouldNotFilter} 內被排除於一般租戶認證之外——因此本類別**不得**對這三個方法呼叫
 * {@link TenantContext#require()}（該 ThreadLocal 對這三個端點不會被設定）；租戶認證改由
 * {@code CrossDeviceLoginService} 依路徑上的 {@code xdevId} capability 反查
 * {@code cross_device_sessions} 自行把關。
 */
@RestController
@RequestMapping("/api/v1/authentication/cross-device/sessions")
public class CrossDeviceLoginController {

    private final CrossDeviceLoginService crossDeviceLoginService;

    public CrossDeviceLoginController(CrossDeviceLoginService crossDeviceLoginService) {
        this.crossDeviceLoginService = crossDeviceLoginService;
    }

    /** 端點 A：購物網站後端建立 session。 */
    @PostMapping
    public ResponseEntity<CrossDeviceSessionCreateResponse> create(
            @Valid @RequestBody CrossDeviceSessionCreateRequest request) {
        return ResponseEntity.ok(crossDeviceLoginService.createSession(TenantContext.require(), request));
    }

    /** 端點 B：手機 App 直連 claim，{@code xdevId} 為 capability，不帶 X-API-Key。 */
    @PostMapping("/{xdevId}/claim")
    public ResponseEntity<CrossDeviceClaimResponse> claim(@PathVariable String xdevId,
                                                           HttpServletRequest httpRequest) {
        return ResponseEntity.ok(crossDeviceLoginService.claim(xdevId, resolveClientIp(httpRequest)));
    }

    /** 端點 C：手機 App 直連提交 assertion result，{@code xdevId} 為 capability，不帶 X-API-Key。 */
    @PostMapping("/{xdevId}/result")
    public ResponseEntity<CrossDeviceResultResponse> result(@PathVariable String xdevId,
                                                             @Valid @RequestBody CrossDeviceResultRequest request,
                                                             HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                crossDeviceLoginService.submitResult(xdevId, request, resolveClientIp(httpRequest)));
    }

    /**
     * 端點 D：購物網站後端輪詢狀態 / 取 JWT。{@code desktopClientIp} 依 api-contract.md 收在
     * query/header（與端點 A 同一個桌機 client IP 值），目前僅接受供呼叫端傳遞一致性使用，
     * 服務層不做額外的 proximity 再比對（proximity 檢查唯一發生於端點 C，見設計文件 5.2.3 節）。
     */
    @GetMapping("/{xdevId}/status")
    public ResponseEntity<CrossDeviceStatusResponse> status(
            @PathVariable String xdevId,
            @RequestParam(required = false) String desktopClientIp) {
        return ResponseEntity.ok(crossDeviceLoginService.pollStatus(TenantContext.require(), xdevId));
    }

    /** 端點 E：手機 App 直連主動放棄（使用者取消 / 本機無憑證），{@code xdevId} 為 capability，不帶 X-API-Key。 */
    @PostMapping("/{xdevId}/deny")
    public ResponseEntity<CrossDeviceDenyResponse> deny(
            @PathVariable String xdevId,
            @RequestBody(required = false) CrossDeviceDenyRequest request) {
        return ResponseEntity.ok(crossDeviceLoginService.deny(xdevId, request));
    }

    /**
     * 手機直連 fido-server（無反向代理時 {@code getRemoteAddr()} 即真實手機 IP）；若部署在
     * 反向代理之後，優先採 {@code X-Forwarded-For} 的第一段（呼叫鏈最前端的真實用戶端 IP）。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
