package com.forgeai.sourcecontrol.infrastructure.persistence.adapter;

import com.forgeai.sourcecontrol.domain.model.WebhookDelivery;
import com.forgeai.sourcecontrol.domain.repository.WebhookDeliveryRepository;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WebhookDeliveryJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.mapper.SourceControlEntityMapper;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataWebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WebhookDeliveryRepositoryAdapter implements WebhookDeliveryRepository {

    private final SpringDataWebhookDeliveryRepository jpaRepository;
    private final SourceControlEntityMapper mapper;

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        if (delivery.getId() != null) {
            Optional<WebhookDeliveryJpaEntity> existingOpt = jpaRepository.findById(delivery.getId());
            if (existingOpt.isPresent()) {
                WebhookDeliveryJpaEntity entity = existingOpt.get();
                entity.setStatus(delivery.getStatus() != null ? delivery.getStatus().name() : entity.getStatus());
                entity.setProcessedAt(delivery.getProcessedAt());
                return mapper.toDomain(jpaRepository.saveAndFlush(entity));
            }
        }
        WebhookDeliveryJpaEntity entity = mapper.toEntity(delivery);
        return mapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<WebhookDelivery> findByDeliveryId(String deliveryId) {
        return jpaRepository.findByDeliveryId(deliveryId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByDeliveryId(String deliveryId) {
        return jpaRepository.existsByDeliveryId(deliveryId);
    }
}
