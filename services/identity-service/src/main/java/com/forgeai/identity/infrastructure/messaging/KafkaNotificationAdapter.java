package com.forgeai.identity.infrastructure.messaging;

import com.forgeai.identity.application.port.NotificationPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaNotificationAdapter implements NotificationPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaNotificationAdapter(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void notify(String userId, String type, String payload) {
        String message = String.format("{\"userId\":\"%s\", \"type\":\"%s\", \"payload\":%s}", userId, type, payload);
        kafkaTemplate.send("notifications-topic", userId, message);
    }
}
