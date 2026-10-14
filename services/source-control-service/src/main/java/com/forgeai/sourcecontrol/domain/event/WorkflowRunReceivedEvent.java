package com.forgeai.sourcecontrol.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by Source Control after a verified GitHub workflow_run webhook is ingested.
 * The Testing Service consumes this event to create or reconcile a TestRun record.
 * <p>
 * Organization ID is derived exclusively from the trusted repository → installation
 * mapping in the source control database — never from the webhook JSON.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunReceivedEvent implements DomainEvent {

    /** ForgeAI internal repository UUID — derived from GitHub repository ID. */
    private UUID repositoryId;

    /** ForgeAI organization UUID — derived from the installation mapping, never from webhook JSON. */
    private UUID organizationId;

    /** Real GitHub workflow_run id (bigint from GitHub). */
    private Long workflowRunId;

    /** GitHub workflow name (e.g. "CI"). */
    private String workflowName;

    /** GitHub run number within the workflow. */
    private Integer runNumber;

    /**
     * GitHub workflow_run status at delivery time.
     * Values: "queued", "in_progress", "completed".
     */
    private String status;

    /**
     * GitHub workflow_run conclusion (null unless status == "completed").
     * Values: "success", "failure", "cancelled", "skipped", "timed_out", etc.
     */
    private String conclusion;

    /** Git branch/ref this run is for. */
    private String headBranch;

    /** Commit SHA this run is for (40-char hex). */
    private String headSha;

    /**
     * The GitHub event that triggered this run (e.g. "push", "pull_request").
     */
    private String triggerEvent;

    /** When the workflow run was created/started at GitHub. */
    private Instant runStartedAt;

    /** When the workflow run completed at GitHub (null if still in progress). */
    private Instant completedAt;

    /** GitHub HTML URL for the run (safe to surface in UI). */
    private String htmlUrl;

    /** Delivery timestamp. */
    private Instant timestamp;

    @Override
    public String getEventType() {
        return "WORKFLOW_RUN_RECEIVED";
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }
}
