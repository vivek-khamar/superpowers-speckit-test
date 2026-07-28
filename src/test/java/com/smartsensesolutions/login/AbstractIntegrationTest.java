package com.smartsensesolutions.login;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

// Singleton-container pattern (start once in a static initializer, never
// stop) -- @Testcontainers/@Container's per-class lifecycle breaks once a
// second @SpringBootTest class shares Spring's cached ApplicationContext
// (and therefore the same container) with this one; the first class's
// afterAll would stop a container the reused context still points at. This
// was found and fixed on DEMO-1 -- applied correctly from the start here.
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("login")
                    .withUsername("login")
                    .withPassword("login");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
