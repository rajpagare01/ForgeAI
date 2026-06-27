package com.forgeai.identity.infrastructure.messaging;

import com.forgeai.identity.application.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdapter.class);

    @Override
    public void notify(String userId, String type, String payload) {
        // In a real system, this might push to a WebSocket broker or Firebase Cloud Messaging
        log.info("Sending notification to user {}: [{}] {}", userId, type, payload);
    }
}
