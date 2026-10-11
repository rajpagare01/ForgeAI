package com.forgeai.project.infrastructure.messaging;

import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.domain.event.UserCreatedEvent;
import com.forgeai.project.domain.event.UserUpdatedEvent;
import com.forgeai.project.domain.model.UserDirectory;
import com.forgeai.project.domain.repository.UserDirectoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityEventConsumer {

    private final UserDirectoryRepository userDirectoryRepository;

    @EventListener
    @Transactional
    public void handleApplicationEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.getPayload() == null) {
            return;
        }

        Object payload = envelope.getPayload();
        if (payload instanceof UserCreatedEvent userCreated) {
            onUserCreated(userCreated);
        } else if (payload instanceof UserUpdatedEvent userUpdated) {
            onUserUpdated(userUpdated);
        }
    }

    public void onUserCreated(UserCreatedEvent event) {
        log.info("Processing UserCreatedEvent for userDirectory projection: userId={}, email={}",
                event.getUserId(), event.getEmail());

        UserDirectory userDirectory = UserDirectory.builder()
                .id(event.getUserId())
                .email(event.getEmail())
                .firstName(event.getFirstName())
                .lastName(event.getLastName())
                .status("ACTIVE")
                .updatedAt(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                .build();

        userDirectoryRepository.save(userDirectory);
    }

    public void onUserUpdated(UserUpdatedEvent event) {
        log.info("Processing UserUpdatedEvent for userDirectory projection: userId={}, status={}",
                event.getUserId(), event.getStatus());

        Optional<UserDirectory> existingOpt = userDirectoryRepository.findById(event.getUserId());
        if (existingOpt.isPresent()) {
            UserDirectory existing = existingOpt.get();
            if (event.getEmail() != null) {
                existing.setEmail(event.getEmail());
            }
            if (event.getFirstName() != null) {
                existing.setFirstName(event.getFirstName());
            }
            if (event.getLastName() != null) {
                existing.setLastName(event.getLastName());
            }
            if (event.getStatus() != null) {
                existing.setStatus(event.getStatus());
            }
            existing.setUpdatedAt(event.getTimestamp() != null ? event.getTimestamp() : Instant.now());
            userDirectoryRepository.save(existing);
        } else {
            UserDirectory newUser = UserDirectory.builder()
                    .id(event.getUserId())
                    .email(event.getEmail() != null ? event.getEmail() : "unknown@example.com")
                    .firstName(event.getFirstName())
                    .lastName(event.getLastName())
                    .status(event.getStatus() != null ? event.getStatus() : "ACTIVE")
                    .updatedAt(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                    .build();
            userDirectoryRepository.save(newUser);
        }
    }
}
