package com.shop.reference.fidoclient;

import com.shop.reference.config.FidoClientProperties;
import com.shop.reference.fidoclient.dto.RegistrationOptionsRequest;
import com.shop.reference.fidoclient.dto.RegistrationOptionsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import jakarta.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 對應任務要求「購物網站後端的代理邏輯本身（options/result 轉發、錯誤處理）要有自動化測試
 * 覆蓋」——本測試不啟動真的 fido-server，而是用 {@link MockRestServiceServer} 綁定
 * {@link FidoServerClient} 實際使用的同一個 {@code RestClient.Builder}（Spring Boot
 * {@code @RestClientTest} 測試切片的標準寫法），驗證：
 * <ul>
 *   <li>對外請求確實帶上 {@code X-API-Key} 這個租戶身分 header；</li>
 *   <li>成功回應能正確反序列化回本專案的 DTO；</li>
 *   <li>fido-server 回傳 docs/api-contract.md 1.4 通用錯誤格式時，會被轉換成攜帶正確
 *       {@code code}/{@code status}/{@code traceId} 的 {@link FidoServerApiException}。</li>
 * </ul>
 */
@RestClientTest(FidoServerClient.class)
@EnableConfigurationProperties(FidoClientProperties.class)
@TestPropertySource(properties = {
        "fido-client.base-url=http://fido-server.test",
        "fido-client.api-key=test-api-key",
        "fido-client.expected-issuer=https://fido.example.internal",
        "fido-client.expected-audience=shop.example.com"
})
class FidoServerClientTest {

    @Resource
    private FidoServerClient fidoServerClient;

    @Resource
    private MockRestServiceServer mockServer;

    @Test
    void registrationOptions_sendsApiKeyHeaderAndParsesSuccessResponse() {
        String responseJson = """
                {
                  "ceremonyId": "reg_9c2f",
                  "publicKey": {
                    "rp": { "id": "shop.example.com", "name": "Example Shop" },
                    "user": { "id": "dXNlci1oYW5kbGU", "name": "u-10023", "displayName": "Demo" },
                    "challenge": "Y2hhbGxlbmdl",
                    "pubKeyCredParams": [ { "type": "public-key", "alg": -7 } ],
                    "timeout": 60000,
                    "attestation": "direct",
                    "authenticatorSelection": {
                      "authenticatorAttachment": "platform",
                      "residentKey": "required",
                      "requireResidentKey": true,
                      "userVerification": "required"
                    },
                    "excludeCredentials": []
                  }
                }
                """;

        mockServer.expect(requestTo("http://fido-server.test/api/v1/registration/options"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        RegistrationOptionsResponse response = fidoServerClient.registrationOptions(
                new RegistrationOptionsRequest("u-10023", "Demo", "我的裝置"));

        assertThat(response.ceremonyId()).isEqualTo("reg_9c2f");
        assertThat(response.publicKey().rp().id()).isEqualTo("shop.example.com");
        assertThat(response.publicKey().attestation()).isEqualTo("direct");
    }

    @Test
    void registrationOptions_mapsFidoServerErrorFormatToApiException() {
        String errorJson = """
                {
                  "error": {
                    "code": "TENANT_DISABLED",
                    "message": "This tenant has been disabled.",
                    "traceId": "req-abc-123",
                    "details": {}
                  }
                }
                """;

        mockServer.expect(requestTo("http://fido-server.test/api/v1/registration/options"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson));

        assertThatThrownBy(() -> fidoServerClient.registrationOptions(
                new RegistrationOptionsRequest("u-10023", null, null)))
                .isInstanceOf(FidoServerApiException.class)
                .satisfies(ex -> {
                    FidoServerApiException apiEx = (FidoServerApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo("TENANT_DISABLED");
                    assertThat(apiEx.getTraceId()).isEqualTo("req-abc-123");
                    assertThat(apiEx.getStatus().value()).isEqualTo(403);
                });
    }

    @Test
    void jwks_isPublicEndpoint_doesNotSendApiKeyHeader() {
        String jwksJson = """
                { "keys": [ { "kty": "EC", "crv": "P-256", "kid": "2026-fido-1", "x": "eA", "y": "eQ", "use": "sig", "alg": "ES256" } ] }
                """;

        mockServer.expect(requestTo("http://fido-server.test/api/v1/.well-known/jwks.json"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("X-API-Key"))
                .andRespond(withSuccess(jwksJson, MediaType.APPLICATION_JSON));

        var jwks = fidoServerClient.jwks();

        assertThat(jwks.keys()).hasSize(1);
        assertThat(jwks.keys().get(0).kid()).isEqualTo("2026-fido-1");
    }
}
