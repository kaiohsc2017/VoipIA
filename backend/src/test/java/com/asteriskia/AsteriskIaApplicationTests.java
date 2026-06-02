package com.asteriskia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AsteriskIaApplicationTests {

    @Test
    void contextLoads() {
        // Verifica se o contexto do Spring Boot inicializa corretamente
        // com as configurações do application-test.properties
    }

}
