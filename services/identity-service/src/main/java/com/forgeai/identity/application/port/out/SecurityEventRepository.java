package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.SecurityEvent;
import java.util.List;
import java.util.UUID;

public interface SecurityEventRepository {
    SecurityEvent save(SecurityEvent entity);
    List<SecurityEvent> findByUserId(UUID userId);
    List<SecurityEvent> findByOrganizationId(UUID organizationId);
}
