package com.forgeai.identity.infrastructure.audit;

import com.forgeai.identity.application.port.AuditLoggerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuditLoggerAdapter implements AuditLoggerPort {

    private static final Logger auditLog = LoggerFactory.getLogger("SECURITY_AUDIT");

    @Override
    public void log(UUID userId, String action, String detail) {
        auditLog.info("AUDIT | USER: {} | ACTION: {} | DETAIL: {}", userId, action, detail);
    }
}
