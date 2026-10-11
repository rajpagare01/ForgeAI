package com.forgeai.sourcecontrol.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {
    private UUID id;
    private String deliveryId;
    private String eventType;
    private String signature;
    private String payload;
    private WebhookDeliveryStatus status;
    private Instant processedAt;
    private Instant createdAt;
}
