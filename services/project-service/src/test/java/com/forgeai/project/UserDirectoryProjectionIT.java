package com.forgeai.project;

import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.domain.event.UserCreatedEvent;
import com.forgeai.project.domain.event.UserUpdatedEvent;
import com.forgeai.project.domain.model.UserDirectory;
import com.forgeai.project.domain.repository.UserDirectoryRepository;
import com.forgeai.project.infrastructure.messaging.IdentityEventConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class UserDirectoryProjectionIT {

    @Autowired
    private IdentityEventConsumer identityEventConsumer;

    @Autowired
    private UserDirectoryRepository userDirectoryRepository;

    @Test
    @DisplayName("UserDirectoryProjectionIT: UserCreatedEvent creates local projection and UserUpdatedEvent updates it")
    void shouldUpsertUserDirectoryOnEvents() {
        UUID userId = UUID.randomUUID();

        // 1. Consume UserCreatedEvent
        UserCreatedEvent createdEvent = UserCreatedEvent.builder()
                .userId(userId)
                .email("alice@forgeai.com")
                .firstName("Alice")
                .lastName("Engineer")
                .timestamp(Instant.now())
                .build();

        EventEnvelope<UserCreatedEvent> createdEnvelope = EventEnvelope.from(createdEvent, "trace-proj-test-1");
        identityEventConsumer.handleApplicationEvent(createdEnvelope);

        // Verify projection created
        Optional<UserDirectory> projection = userDirectoryRepository.findById(userId);
        assertThat(projection).isPresent();
        assertThat(projection.get().getEmail()).isEqualTo("alice@forgeai.com");
        assertThat(projection.get().getFirstName()).isEqualTo("Alice");
        assertThat(projection.get().getLastName()).isEqualTo("Engineer");
        assertThat(projection.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(projection.get().isActive()).isTrue();

        // 2. Consume UserUpdatedEvent
        UserUpdatedEvent updatedEvent = UserUpdatedEvent.builder()
                .userId(userId)
                .email("alice.modified@forgeai.com")
                .firstName("Alice")
                .lastName("Senior")
                .status("SUSPENDED")
                .timestamp(Instant.now())
                .build();

        EventEnvelope<UserUpdatedEvent> updatedEnvelope = EventEnvelope.from(updatedEvent, "trace-proj-test-2");
        identityEventConsumer.handleApplicationEvent(updatedEnvelope);

        // Verify projection updated
        Optional<UserDirectory> updatedProjection = userDirectoryRepository.findById(userId);
        assertThat(updatedProjection).isPresent();
        assertThat(updatedProjection.get().getEmail()).isEqualTo("alice.modified@forgeai.com");
        assertThat(updatedProjection.get().getLastName()).isEqualTo("Senior");
        assertThat(updatedProjection.get().getStatus()).isEqualTo("SUSPENDED");
        assertThat(updatedProjection.get().isActive()).isFalse();
    }
}
