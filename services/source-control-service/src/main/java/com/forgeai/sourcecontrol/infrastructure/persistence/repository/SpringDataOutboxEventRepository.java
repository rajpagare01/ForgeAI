package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
        SELECT * FROM outbox_events 
        WHERE published_at IS NULL 
          AND (status IS NULL OR status IN ('PENDING', 'RETRYING'))
          AND (next_retry_at IS NULL OR next_retry_at <= NOW())
        ORDER BY created_at ASC 
        LIMIT :batchSize 
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedEventsForProcessing(@Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM outbox_events 
        WHERE published_at IS NULL 
          AND (status IS NULL OR status IN ('PENDING', 'RETRYING'))
          AND (next_retry_at IS NULL OR next_retry_at <= NOW())
        ORDER BY created_at ASC 
        LIMIT 100
        """, nativeQuery = true)
    List<OutboxEventEntity> findTop100PendingOrRetrying();

    List<OutboxEventEntity> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    long countByPublishedAtIsNull();

    long countByStatus(String status);

    List<OutboxEventEntity> findByStatusOrderByDeadLetterAtDesc(String status, Pageable pageable);
}
