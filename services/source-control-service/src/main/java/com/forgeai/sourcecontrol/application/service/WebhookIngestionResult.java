package com.forgeai.sourcecontrol.application.service;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WebhookIngestionResult {
    String deliveryId;
    String status;
    boolean idempotentHit;
}
