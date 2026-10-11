package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query(value = """
        SELECT * FROM outbox_events 
        WHERE published_at IS NULL 
        ORDER BY created_at ASC 
        LIMIT :batchSize 
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> findUnpublishedEventsForProcessing(@Param("batchSize") int batchSize);

    List<OutboxEventEntity> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
