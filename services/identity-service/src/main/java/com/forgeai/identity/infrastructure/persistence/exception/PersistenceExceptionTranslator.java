package com.forgeai.identity.infrastructure.persistence.exception;

import com.forgeai.identity.infrastructure.exception.InfrastructureException;
import org.springframework.dao.DataAccessException;
import jakarta.persistence.PersistenceException;

/**
 * Translates JPA/Hibernate exceptions into generic Infrastructure exceptions.
 */
public final class PersistenceExceptionTranslator {

    private PersistenceExceptionTranslator() {
        // Utility class
    }

    public static InfrastructureException translate(Exception ex) {
        if (ex instanceof DataAccessException) {
            return new InfrastructureException("Database access error occurred", ex);
        } else if (ex instanceof PersistenceException) {
            return new InfrastructureException("JPA persistence error occurred", ex);
        } else {
            return new InfrastructureException("Unexpected infrastructure error occurred", ex);
        }
    }
}
