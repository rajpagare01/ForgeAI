package com.forgeai.project.infrastructure.client;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CircuitBreakerRegistry {

    private final Map<String, SimpleCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public void register(SimpleCircuitBreaker breaker) {
        breakers.put(breaker.getServiceName(), breaker);
    }

    public Map<String, SimpleCircuitBreaker> getAllBreakers() {
        return breakers;
    }

    public SimpleCircuitBreaker getBreaker(String serviceName) {
        return breakers.get(serviceName);
    }
}
