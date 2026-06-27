package com.forgeai.identity.application.port;

import java.util.function.Supplier;

/**
 * Output port for managing transaction boundaries.
 * Implemented in the infrastructure layer using Spring's transaction manager.
 * Keeps the application layer free of @Transactional annotations.
 */
public interface TransactionManagerPort {

    /**
     * Executes the given action within a single transaction.
     *
     * @param action the business logic to execute transactionally
     * @param <T>    the return type
     * @return the result of the action
     */
    <T> T executeInTransaction(Supplier<T> action);

    /**
     * Executes the given action within a single transaction (void variant).
     *
     * @param action the business logic to execute transactionally
     */
    void executeInTransaction(Runnable action);
}
