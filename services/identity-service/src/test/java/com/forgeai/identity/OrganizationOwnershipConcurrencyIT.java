package com.forgeai.identity;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.service.OrganizationService;
import com.forgeai.identity.domain.exception.InvalidOwnershipOperationException;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.domain.model.UserStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrganizationOwnershipConcurrencyIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            "postgres:15-alpine"
    );

    @BeforeAll
    static void beforeAll() {
        postgres.start();
    }

    @AfterAll
    static void afterAll() {
        postgres.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;
    
    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    private User createUser(String name) {
        User user = new User();
        user.setEmail(new Email(name + "@example.com"));
        user.setUsername(new Username(name));
        user.setPasswordHash("hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Test
    void testConcurrentOwnerDowngrade() throws InterruptedException {
        User creator = createUser("creator1");
        Organization org = organizationService.createOrganization("Concurrent Downgrade Org", "concurrent-downgrade-org", "desc", creator.getId());
        
        User owner2 = createUser("owner2");
        organizationService.addOrganizationMember(org.getId(), creator.getId(), owner2.getId(), "MEMBER");
        organizationService.changeOrganizationMemberRole(org.getId(), creator.getId(), owner2.getId(), "OWNER");

        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        // We have 2 owners. 10 threads will concurrently try to downgrade both of them to MEMBER.
        // At least one operation must fail, leaving 1 OWNER.
        for (int i = 0; i < 5; i++) {
            futures.add(executorService.submit(() -> {
                latch.await();
                try {
                    organizationService.changeOrganizationMemberRole(org.getId(), creator.getId(), creator.getId(), "MEMBER");
                    return true; // Success
                } catch (InvalidOwnershipOperationException e) {
                    return false; // Failed as expected due to last owner protection
                }
            }));
            futures.add(executorService.submit(() -> {
                latch.await();
                try {
                    organizationService.changeOrganizationMemberRole(org.getId(), owner2.getId(), owner2.getId(), "MEMBER");
                    return true;
                } catch (InvalidOwnershipOperationException e) {
                    return false;
                }
            }));
        }

        latch.countDown(); // start all threads
        
        int successCount = 0;
        int failureCount = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (future.get()) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof InvalidOwnershipOperationException) {
                    failureCount++;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
        
        executorService.shutdown();
        
        // Assert that the invariant holds
        Role ownerRole = roleRepository.findSystemRoleByName("OWNER").orElseThrow();
        long finalOwnerCount = membershipRepository.countByOrganizationIdAndRoleId(org.getId(), ownerRole.getId());
        
        assertTrue(finalOwnerCount >= 1, "At least one owner must remain");
        assertTrue(failureCount > 0, "Some operations must have been rejected to preserve the last owner");
    }
}
