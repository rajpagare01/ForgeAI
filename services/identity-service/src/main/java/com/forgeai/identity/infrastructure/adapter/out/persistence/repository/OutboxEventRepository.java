package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND attempt_count < :maxAttempts
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findUnprocessedEventsForUpdateSkipLocked(
            @Param("batchSize") int batchSize,
            @Param("maxAttempts") int maxAttempts
    );
}
