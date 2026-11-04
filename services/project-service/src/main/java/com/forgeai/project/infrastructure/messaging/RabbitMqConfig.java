package com.forgeai.project.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "forgeai.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    public static final String EXCHANGE_NAME = "forgeai.events.exchange";
    public static final String DLX_NAME = "forgeai.events.dlx";
    public static final String DLQ_NAME = "forgeai.events.dlq";

    public static final String PROJECT_IDENTITY_QUEUE = "forgeai.project.identity.queue";
    public static final String PROJECT_SOURCECONTROL_QUEUE = "forgeai.project.sourcecontrol.queue";

    @Bean
    public TopicExchange forgeAiEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_NAME).durable(true).build();
    }

    @Bean
    public TopicExchange forgeAiDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(DLX_NAME).durable(true).build();
    }

    @Bean
    public Queue forgeAiDeadLetterQueue() {
        return QueueBuilder.durable(DLQ_NAME).build();
    }

    @Bean
    public Binding bindDeadLetterQueue(Queue forgeAiDeadLetterQueue, TopicExchange forgeAiDeadLetterExchange) {
        return BindingBuilder.bind(forgeAiDeadLetterQueue).to(forgeAiDeadLetterExchange).with("#");
    }

    @Bean
    public Queue projectIdentityQueue() {
        return QueueBuilder.durable(PROJECT_IDENTITY_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", "dlq.project.identity")
                .build();
    }

    @Bean
    public Binding bindProjectIdentityQueue(Queue projectIdentityQueue, TopicExchange forgeAiEventsExchange) {
        return BindingBuilder.bind(projectIdentityQueue).to(forgeAiEventsExchange).with("forgeai.identity.#");
    }

    @Bean
    public Queue projectSourceControlQueue() {
        return QueueBuilder.durable(PROJECT_SOURCECONTROL_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_NAME)
                .withArgument("x-dead-letter-routing-key", "dlq.project.sourcecontrol")
                .build();
    }

    @Bean
    public Binding bindProjectSourceControlQueue(Queue projectSourceControlQueue, TopicExchange forgeAiEventsExchange) {
        return BindingBuilder.bind(projectSourceControlQueue).to(forgeAiEventsExchange).with("forgeai.sourcecontrol.#");
    }
}

