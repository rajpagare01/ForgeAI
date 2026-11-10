package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.application.handler.InstallationEventHandler;
import com.forgeai.sourcecontrol.application.handler.PullRequestEventHandler;
import com.forgeai.sourcecontrol.application.handler.PushEventHandler;
import com.forgeai.sourcecontrol.application.handler.WorkflowRunEventHandler;
import com.forgeai.sourcecontrol.domain.model.WebhookDelivery;
import com.forgeai.sourcecontrol.domain.model.WebhookDeliveryStatus;
import com.forgeai.sourcecontrol.domain.repository.WebhookDeliveryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookIngestionServiceHotfixTest {

    @Mock
    private GitHubSignatureVerifier signatureVerifier;

    @Mock
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Mock
    private PushEventHandler pushEventHandler;

    @Mock
    private PullRequestEventHandler pullRequestEventHandler;

    @Mock
    private InstallationEventHandler installationEventHandler;

    @Mock
    private WorkflowRunEventHandler workflowRunEventHandler;

    private WebhookIngestionService webhookIngestionService;

    @BeforeEach
    void setUp() {
        webhookIngestionService = new WebhookIngestionService(
                signatureVerifier,
                webhookDeliveryRepository,
                pushEventHandler,
                pullRequestEventHandler,
                installationEventHandler,
                workflowRunEventHandler
        );
    }

    @Test
    @DisplayName("ingestWebhook marks WebhookDeliveryStatus.FAILED before rethrowing DataIntegrityViolationException")
    void marksDeliveryFailedOnDataIntegrityViolation() {
        byte[] payload = "{\"action\":\"created\"}".getBytes(StandardCharsets.UTF_8);
        when(webhookDeliveryRepository.existsByDeliveryId("deliv-constraint-1")).thenReturn(false);
        when(webhookDeliveryRepository.save(any(WebhookDelivery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("chk_gh_install_scope violated"))
                .when(installationEventHandler)
                .handle(any(), eq("deliv-constraint-1"));

        assertThatThrownBy(() -> webhookIngestionService.ingestWebhook(
                "deliv-constraint-1",
                "installation",
                "sha256=test",
                payload
        ))
                .isInstanceOf(DataIntegrityViolationException.class);

        ArgumentCaptor<WebhookDelivery> deliveryCaptor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(webhookDeliveryRepository, times(2)).save(deliveryCaptor.capture());
        List<WebhookDelivery> saves = deliveryCaptor.getAllValues();
        assertThat(saves).hasSize(2);
        assertThat(saves.get(1).getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }
}

