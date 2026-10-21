package com.forgeai.sourcecontrol.infrastructure.metrics;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class InstallationTelemetryReconciler {

    private final GitHubInstallationRepository installationRepository;
    private final SourceControlMetrics metrics;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reconcile();
    }

    public synchronized void reconcile() {
        try {
            List<GitHubInstallation> all = installationRepository.findAll();
            metrics.reconcileInstallations(all);
            log.info("Authoritative GitHub installation telemetry reconciled: active={}, quarantined={}, suspended={}",
                    metrics.getActiveInstallations(),
                    metrics.getQuarantinedInstallations(),
                    metrics.getSuspendedInstallations());
        } catch (Exception e) {
            log.error("Failed to reconcile GitHub installation telemetry: {}", e.getMessage(), e);
        }
    }
}
