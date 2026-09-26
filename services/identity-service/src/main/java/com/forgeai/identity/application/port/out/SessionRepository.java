package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Session;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface SessionRepository {
    Optional<Session> findById(UUID id);
    List<Session> findActiveByUserId(UUID userId);
    Session save(Session entity);
}
