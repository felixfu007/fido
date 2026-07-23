package com.shop.reference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 基本 context load smoke test：確認所有 bean（含 FidoServerClient/JwtValidator）可以正常組裝。 */
@SpringBootTest
@TestPropertySource(properties = {
        "fido-client.base-url=http://localhost:8443",
        "fido-client.api-key=dev-api-key-00000000000000000000",
        "fido-client.expected-issuer=https://fido.example.internal",
        "fido-client.expected-audience=shop.example.com"
})
class ShoppingSiteReferenceApplicationTests {

    @Test
    void contextLoads() {
    }
}
