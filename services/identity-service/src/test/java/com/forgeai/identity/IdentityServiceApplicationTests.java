package com.forgeai.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Basic Spring context startup test.
 *
 * DataSource, JPA, and Flyway are excluded here intentionally — the context
 * load test should not require a running PostgreSQL instance. Integration
 * tests that require the database live in PersistenceInvariantTest.
 */
@SpringBootTest(
    classes = IdentityServiceApplication.class,
    properties = "spring.flyway.enabled=false"
)
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
class IdentityServiceApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors,
        // with all database auto-configurations excluded.
    }
}

