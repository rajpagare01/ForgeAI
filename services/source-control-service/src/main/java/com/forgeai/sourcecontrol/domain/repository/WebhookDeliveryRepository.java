package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.WebhookDelivery;

import java.util.Optional;

public interface WebhookDeliveryRepository {
    WebhookDelivery save(WebhookDelivery delivery);
    Optional<WebhookDelivery> findByDeliveryId(String deliveryId);
    boolean existsByDeliveryId(String deliveryId);
}
