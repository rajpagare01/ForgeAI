package com.forgeai.identity.infrastructure.persistence.adapter;

import com.forgeai.identity.application.port.TransactionManagerPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class SpringTransactionManagerAdapter implements TransactionManagerPort {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionManagerAdapter(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T executeInTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    @Override
    public void executeInTransaction(Runnable action) {
        transactionTemplate.execute(status -> {
            action.run();
            return null;
        });
    }
}
