package com.forgeai.testing.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.domain.event.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class LocalSpringEventPublisher implements EventPublisher {

    private static final String ROUTING_PREFIX = "forgeai.testing.";

    private final ApplicationEventPublisher applicationEventPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean rabbitMqEnabled;
    private final String exchangeName;

    public LocalSpringEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this(applicationEventPublisher, null, null, false, "forgeai.events.exchange");
    }

    @Autowired
    public LocalSpringEventPublisher(
            ApplicationEventPublisher applicationEventPublisher,
            @Autowired(required = false) RabbitTemplate rabbitTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Value("${forgeai.rabbitmq.enabled:false}") boolean rabbitMqEnabled,
            @Value("${forgeai.rabbitmq.exchange:forgeai.events.exchange}") String exchangeName) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.rabbitMqEnabled = rabbitMqEnabled;
        this.exchangeName = exchangeName;
    }

    @Override
    public void publish(EventEnvelope<?> envelope) {
        log.info("Publishing event to Spring application context: type={}, aggregateId={}",
                envelope.getEventType(), envelope.getAggregateId());
        applicationEventPublisher.publishEvent(envelope);

        if (rabbitMqEnabled && rabbitTemplate != null) {
            try {
                ObjectMapper mapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper().findAndRegisterModules();
                String json = mapper.writeValueAsString(envelope);
                String routingKey = ROUTING_PREFIX + (envelope.getEventType() != null ? envelope.getEventType().toLowerCase() : "unknown");

                MessageProperties props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setContentEncoding(StandardCharsets.UTF_8.name());
                if (envelope.getEventType() != null) {
                    props.setHeader("X-Event-Type", envelope.getEventType());
                }
                if (envelope.getTraceId() != null) {
                    props.setHeader("X-Trace-Id", envelope.getTraceId());
                }

                Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
                rabbitTemplate.send(exchangeName, routingKey, message);
                log.info("[RabbitMQ] Published event to exchange={} routingKey={} eventId={} aggregateId={}",
                        exchangeName, routingKey, envelope.getEventId(), envelope.getAggregateId());
            } catch (Exception ex) {
                log.error("[RabbitMQ] Failed to publish event to exchange={}: {}", exchangeName, ex.getMessage(), ex);
                throw new IllegalStateException("RabbitMQ publish failed for event " + envelope.getEventType() + ": " + ex.getMessage(), ex);
            }
        }
    }
}
