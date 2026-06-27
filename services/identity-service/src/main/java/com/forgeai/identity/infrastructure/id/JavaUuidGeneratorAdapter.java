package com.forgeai.identity.infrastructure.id;

import com.forgeai.identity.application.port.UuidGeneratorPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JavaUuidGeneratorAdapter implements UuidGeneratorPort {
    @Override
    public UUID generate() {
        return UUID.randomUUID();
    }
}
