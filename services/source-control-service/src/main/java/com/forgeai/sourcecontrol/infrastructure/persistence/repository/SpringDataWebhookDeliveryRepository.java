package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WebhookDeliveryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataWebhookDeliveryRepository extends JpaRepository<WebhookDeliveryJpaEntity, UUID> {
    Optional<WebhookDeliveryJpaEntity> findByDeliveryId(String deliveryId);
    boolean existsByDeliveryId(String deliveryId);
}
