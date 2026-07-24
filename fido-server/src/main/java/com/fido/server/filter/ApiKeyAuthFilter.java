package com.fido.server.filter;

import com.fido.server.context.TenantContext;
import com.fido.server.domain.Tenant;
import com.fido.server.domain.enums.TenantStatus;
import com.fido.server.repository.TenantRepository;
import com.fido.server.service.ApiKeyService;
import com.fido.server.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 【真實實作】依 api-contract.md 1.2 實作 {@code X-API-Key} 租戶認證：
 * <ul>
 *   <li>缺少 / 無效 API Key -&gt; 401 UNAUTHENTICATED</li>
 *   <li>{@code X-Tenant-Id} 與 API Key 對應租戶不符 -&gt; 403 TENANT_MISMATCH</li>
 *   <li>租戶停用 -&gt; 403 TENANT_DISABLED</li>
 *   <li>超過速率限制 -&gt; 429 RATE_LIMITED + {@code Retry-After}</li>
 * </ul>
 * 公開端點（JWKS、actuator health）不需 API Key，見 {@link #PUBLIC_PATH_PREFIXES}。
 *
 * <p><b>跨裝置 QR 登入（情境三）capability 認證例外</b>（api-contract.md §1.2.2 / D16）：
 * claim（{@code POST .../cross-device/sessions/{xdevId}/claim}）、result
 * （{@code POST .../cross-device/sessions/{xdevId}/result}）與 deny
 * （{@code POST .../cross-device/sessions/{xdevId}/deny}，§3.4.E）三個端點由手機 App 直連，
 * **不帶** {@code X-API-Key}，改以路徑上的不透明 {@code xdevId} 作 capability 認證
 * （租戶由 {@code xdevId} 反查，見 {@code CrossDeviceLoginService}）。這三個端點因此也繞過本
 * filter（見 {@link #XDEV_CAPABILITY_PATH}），但**不是**公開端點——{@code TenantContext} 對
 * 這三個端點不會被設定，{@code CrossDeviceLoginController} 不得呼叫
 * {@code TenantContext.require()}，租戶認證完全由 service 層依 {@code xdevId} 查
 * {@code cross_device_sessions} 自行把關。§3.4 另兩個端點（A 建立 session、D 輪詢狀態）
 * 仍由購物網站後端以 {@code X-API-Key} 呼叫，不受此例外影響。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/.well-known/jwks.json",
            "/actuator"
    );

    private static final Pattern XDEV_CAPABILITY_PATH = Pattern.compile(
            "^/api/v1/authentication/cross-device/sessions/[^/]+/(claim|result|deny)$");

    private final TenantRepository tenantRepository;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final FilterErrorWriter errorWriter;

    public ApiKeyAuthFilter(TenantRepository tenantRepository, ApiKeyService apiKeyService,
                             RateLimitService rateLimitService, FilterErrorWriter errorWriter) {
        this.tenantRepository = tenantRepository;
        this.apiKeyService = apiKeyService;
        this.rateLimitService = rateLimitService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith)
                || XDEV_CAPABILITY_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String apiKey = request.getHeader(HEADER_API_KEY);
            if (apiKey == null || apiKey.isBlank()) {
                errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED",
                        "Missing X-API-Key header.");
                return;
            }

            byte[] hash = apiKeyService.hash(apiKey);
            Optional<Tenant> tenantOpt = tenantRepository.findByApiKeyHash(hash);
            if (tenantOpt.isEmpty()) {
                errorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED",
                        "Invalid API Key.");
                return;
            }
            Tenant tenant = tenantOpt.get();

            String tenantIdHeader = request.getHeader(HEADER_TENANT_ID);
            if (tenantIdHeader != null && !tenantIdHeader.isBlank()
                    && !tenant.getTenantUid().equals(safeParseUuid(tenantIdHeader))) {
                errorWriter.write(response, HttpStatus.FORBIDDEN.value(), "TENANT_MISMATCH",
                        "X-Tenant-Id does not match the tenant resolved from X-API-Key.");
                return;
            }

            if (tenant.getStatus() != TenantStatus.ACTIVE) {
                errorWriter.write(response, HttpStatus.FORBIDDEN.value(), "TENANT_DISABLED",
                        "This tenant has been disabled.");
                return;
            }

            if (!rateLimitService.tryAcquire(tenant)) {
                response.setHeader("Retry-After", "1");
                errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED",
                        "Rate limit exceeded for this tenant.");
                return;
            }

            TenantContext.set(tenant);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID safeParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID(); // 必定不等於任何真實 tenantUid，觸發 TENANT_MISMATCH
        }
    }
}
