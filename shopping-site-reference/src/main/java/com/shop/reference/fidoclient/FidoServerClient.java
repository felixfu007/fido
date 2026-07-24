package com.shop.reference.fidoclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.reference.config.FidoClientProperties;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsRequest;
import com.shop.reference.fidoclient.dto.AuthenticationOptionsResponse;
import com.shop.reference.fidoclient.dto.AuthenticationResultRequest;
import com.shop.reference.fidoclient.dto.AuthenticationResultResponse;
import com.shop.reference.fidoclient.dto.CrossDeviceStartRequest;
import com.shop.reference.fidoclient.dto.CrossDeviceStartResponse;
import com.shop.reference.fidoclient.dto.CrossDeviceStatusResponse;
import com.shop.reference.fidoclient.dto.DeviceListResponse;
import com.shop.reference.fidoclient.dto.DeviceRevokeResponse;
import com.shop.reference.fidoclient.dto.FidoErrorResponse;
import com.shop.reference.fidoclient.dto.FidoStatusResponse;
import com.shop.reference.fidoclient.dto.JwkSetResponse;
import com.shop.reference.fidoclient.dto.RegistrationOptionsRequest;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import com.shop.reference.fidoclient.dto.RegistrationResultRequest;
import com.shop.reference.fidoclient.dto.RegistrationResultResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 購物網站後端呼叫 fido-server REST API 的唯一入口（server-to-server，見
 * docs/api-contract.md 1.2）。所有方法皆會帶上：
 * <ul>
 *   <li>{@code X-API-Key}：租戶身分憑證（必）</li>
 *   <li>{@code X-Tenant-Id}：選填交叉檢查（若設定值不為空才會帶）</li>
 *   <li>{@code X-Request-Id}：每次呼叫自帶追蹤 ID，方便跨系統對照 log</li>
 * </ul>
 *
 * <p>非 2xx 回應一律由建構子內註冊的 {@code defaultStatusHandler} 攔截，解析為 {@link FidoServerApiException}
 * （攜帶 fido-server 的 {@code code}/{@code traceId}），讓上層可以照
 * docs/api-contract.md 1.4 的錯誤碼表分流，而不是接到一個籠統的 {@code RestClientException}。
 *
 * <p>建構子刻意接受 {@link RestClient.Builder} 而非直接注入 {@link RestClient}：
 * 這是 Spring Boot {@code @RestClientTest} 的標準寫法，測試時可以用
 * {@code MockRestServiceServer} 綁定同一個 builder，完全不需要真的啟動 fido-server
 * 就能驗證本專案「代理邏輯本身」（options/result 轉發、錯誤處理）的正確性。
 */
@Component
public class FidoServerClient {

    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private final RestClient restClient;
    private final FidoClientProperties properties;

    public FidoServerClient(RestClient.Builder restClientBuilder, FidoClientProperties properties,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toApiException(response, objectMapper);
                })
                .build();
    }

    public RegistrationOptionsResponse registrationOptions(RegistrationOptionsRequest request) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/v1/registration/options");
        applyAuthHeaders(spec);
        return spec.body(request).retrieve().body(RegistrationOptionsResponse.class);
    }

    public RegistrationResultResponse registrationResult(RegistrationResultRequest request) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/v1/registration/result");
        applyAuthHeaders(spec);
        return spec.body(request).retrieve().body(RegistrationResultResponse.class);
    }

    public AuthenticationOptionsResponse authenticationOptions(AuthenticationOptionsRequest request) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/v1/authentication/options");
        applyAuthHeaders(spec);
        return spec.body(request).retrieve().body(AuthenticationOptionsResponse.class);
    }

    public AuthenticationResultResponse authenticationResult(AuthenticationResultRequest request) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/v1/authentication/result");
        applyAuthHeaders(spec);
        return spec.body(request).retrieve().body(AuthenticationResultResponse.class);
    }

    /** 對應 docs/api-contract.md §3.4.A：由購物網站後端發起，建立跨裝置 QR 登入 session。 */
    public CrossDeviceStartResponse crossDeviceStart(CrossDeviceStartRequest request) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/v1/authentication/cross-device/sessions");
        applyAuthHeaders(spec);
        return spec.body(request).retrieve().body(CrossDeviceStartResponse.class);
    }

    /**
     * 對應 docs/api-contract.md §3.4.D：由購物網站後端代桌機輪詢跨裝置 QR 登入狀態 / 取
     * session JWT。{@code desktopClientIp} 依合約「query/header 皆可」，本實作採 query 參數。
     */
    public CrossDeviceStatusResponse crossDeviceStatus(String xdevId, String desktopClientIp) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(uriBuilder -> uriBuilder
                .path("/api/v1/authentication/cross-device/sessions/{xdevId}/status")
                .queryParam("desktopClientIp", desktopClientIp)
                .build(xdevId));
        applyAuthHeaders(spec);
        return spec.retrieve().body(CrossDeviceStatusResponse.class);
    }

    public DeviceListResponse listDevices(String externalUserId, String status, int limit) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(uriBuilder -> uriBuilder
                .path("/api/v1/users/{externalUserId}/devices")
                .queryParam("status", status)
                .queryParam("limit", limit)
                .build(externalUserId));
        applyAuthHeaders(spec);
        return spec.retrieve().body(DeviceListResponse.class);
    }

    public DeviceRevokeResponse revokeDevice(String externalUserId, String deviceId) {
        RestClient.RequestHeadersSpec<?> spec = restClient.delete()
                .uri("/api/v1/users/{externalUserId}/devices/{deviceId}", externalUserId, deviceId);
        applyAuthHeaders(spec);
        return spec.retrieve().body(DeviceRevokeResponse.class);
    }

    public FidoStatusResponse fidoStatus(String externalUserId) {
        RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri("/api/v1/users/{externalUserId}/fido-status", externalUserId);
        applyAuthHeaders(spec);
        return spec.retrieve().body(FidoStatusResponse.class);
    }

    /** JWKS 為公開端點，不需要 API Key（docs/api-contract.md 3.3），因此不套用 {@link #applyAuthHeaders}。 */
    public JwkSetResponse jwks() {
        return restClient.get()
                .uri("/api/v1/.well-known/jwks.json")
                .header(HEADER_REQUEST_ID, "shop-" + UUID.randomUUID())
                .retrieve()
                .body(JwkSetResponse.class);
    }

    /**
     * 在既有的 request spec 上「就地」附加共用 headers（{@code header()} 本身是有副作用的
     * builder 方法，回傳值與接收者是同一個物件，故不需要、也刻意不透過泛型回傳新物件 ——
     * 那樣做在 {@code RequestHeadersSpec<?>} 這種萬用字元型別上會撞上 Java 泛型型別推斷的
     * 已知限制（capture of ? 無法對應方法簽章的具名型別變數）。
     */
    private void applyAuthHeaders(RestClient.RequestHeadersSpec<?> spec) {
        spec.header(HEADER_API_KEY, properties.getApiKey());
        spec.header(HEADER_REQUEST_ID, "shop-" + UUID.randomUUID());
        if (properties.getTenantId() != null && !properties.getTenantId().isBlank()) {
            spec.header(HEADER_TENANT_ID, properties.getTenantId());
        }
    }

    private static FidoServerApiException toApiException(ClientHttpResponse response, ObjectMapper objectMapper)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        byte[] bodyBytes;
        try (var in = response.getBody()) {
            bodyBytes = in.readAllBytes();
        }
        try {
            FidoErrorResponse body = objectMapper.readValue(bodyBytes, FidoErrorResponse.class);
            if (body != null && body.error() != null) {
                return new FidoServerApiException(status, body.error().code(), body.error().message(),
                        body.error().traceId(), body.error().details());
            }
        } catch (Exception parseFailure) {
            // fall through：body 不是預期的通用錯誤格式（例如非 JSON 的 502/504），仍要回一個可分流的例外
        }
        return new FidoServerApiException(status, "UPSTREAM_ERROR",
                "fido-server 回應非預期格式的錯誤（HTTP " + status.value() + "）。", null, Map.of());
    }
}
