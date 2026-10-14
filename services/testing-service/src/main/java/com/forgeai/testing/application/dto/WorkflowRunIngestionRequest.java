package com.forgeai.testing.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal request DTO for ingesting a GitHub workflow_run event into the Testing Service.
 * <p>
 * Fields are mapped directly from {@code WorkflowRunReceivedEvent} published by Source Control.
 * Organization ID is trusted because it was derived from the installation mapping in
 * Source Control — it is never taken from raw GitHub webhook JSON.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowRunIngestionRequest {

    /** ForgeAI repository UUID — derived from GitHub repository ID. */
    private UUID repositoryId;

    /** ForgeAI organization UUID — trusted: derived from installation mapping in Source Control. */
    private UUID organizationId;

    /** Real GitHub workflow_run id. */
    private Long workflowRunId;

    /** GitHub workflow name. */
    private String workflowName;

    /** GitHub run number. */
    private Integer runNumber;

    /**
     * GitHub workflow_run status at delivery time.
     * One of: "queued", "in_progress", "completed".
     */
    private String status;

    /**
     * GitHub workflow_run conclusion (null unless status == "completed").
     * One of: "success", "failure", "cancelled", "skipped", "timed_out", etc.
     */
    private String conclusion;

    /** Git branch/ref this run is for. */
    private String headBranch;

    /** Commit SHA this run is for (40-char hex). */
    private String headSha;

    /** The GitHub event that triggered this run (e.g. "push", "pull_request"). */
    private String triggerEvent;

    /** When the workflow run started at GitHub. */
    private Instant runStartedAt;

    /** When the workflow run completed at GitHub (null if not yet completed). */
    private Instant completedAt;

    /** GitHub HTML URL for the run. */
    private String htmlUrl;

    /** Delivery timestamp from Source Control. */
    private Instant timestamp;
}
