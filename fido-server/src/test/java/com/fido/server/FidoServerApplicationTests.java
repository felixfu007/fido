package com.fido.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FidoServerApplicationTests {

    @Test
    void contextLoads() {
        // 驗證 Spring 容器（含所有 @Component/@Service/filter/CommandLineRunner）可正常啟動。
    }
}
