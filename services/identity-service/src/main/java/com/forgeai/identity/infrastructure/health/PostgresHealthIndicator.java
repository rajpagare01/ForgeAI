package com.forgeai.identity.infrastructure.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Custom health indicator for Postgres. Actuator auto-configures one,
 * but this explicitly fulfills the requirement.
 */
@Component("postgresqlHealthIndicator")
public class PostgresHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public PostgresHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(1000)) {
                return Health.up().withDetail("database", "PostgreSQL is reachable").build();
            } else {
                return Health.down().withDetail("database", "Connection invalid").build();
            }
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
