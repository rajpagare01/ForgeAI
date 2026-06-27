package com.forgeai.identity.infrastructure.time;

import com.forgeai.identity.application.port.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
