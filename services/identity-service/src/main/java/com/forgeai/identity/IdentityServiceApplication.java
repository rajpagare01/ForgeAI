package com.forgeai.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

/**
 * Identity Service Application
 *
 * This service is responsible for:
 * - User identity management
 * - Organization and team management
 * - Authentication (login, sessions, tokens)
 * - Authorization (RBAC: roles and permissions)
 * - Security event auditing
 *
 * Spring Security is on the classpath but its auto-configuration is excluded
 * until we implement the security configuration explicitly.
 * DataSource and Flyway are active and connected to PostgreSQL.
 */
@SpringBootApplication(exclude = {
    SecurityAutoConfiguration.class,
    ManagementWebSecurityAutoConfiguration.class
})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
