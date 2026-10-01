package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.domain.model.SecurityEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private final SecurityEventRepository securityEventRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(UUID userId, UUID organizationId, String eventType, String ipAddress, String userAgent, String details) {
        SecurityEvent event = new SecurityEvent();
        event.setUserId(userId);
        event.setOrganizationId(organizationId);
        event.setEventType(eventType);
        event.setIpAddress(ipAddress);
        event.setUserAgent(userAgent);
        event.setDetails(details); // Should be a valid JSON string per db schema
        event.setCreatedAt(Instant.now());
        
        securityEventRepository.save(event);
    }
}
