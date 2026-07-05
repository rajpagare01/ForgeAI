package com.forgeai.identity.infrastructure.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component("customKafkaHealthIndicator")
public class KafkaHealthIndicator implements HealthIndicator {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    public KafkaHealthIndicator(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }
    
    @Override
    public Health health() {
        try {
            kafkaTemplate.metrics();
            return Health.up().withDetail("Kafka", "Available").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
