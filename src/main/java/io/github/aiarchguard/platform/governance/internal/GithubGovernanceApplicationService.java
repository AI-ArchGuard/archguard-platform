package io.github.aiarchguard.platform.governance.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GithubGovernanceOperations;
import io.github.aiarchguard.platform.governance.GithubLinkNotFoundException;
import io.github.aiarchguard.platform.governance.GithubPullRequestNotFoundException;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import io.github.aiarchguard.platform.governance.GithubWebhookResult;
import io.github.aiarchguard.platform.governance.GithubWebhookUnavailableException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGithubWebhookException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GithubGovernanceApplicationService implements GithubGovernanceOperations {
    private static final int MAX_WEBHOOK_BYTES = 2 * 1024 * 1024;
    private static final Duration EVENT_WINDOW = Duration.ofMinutes(10);
    private static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened");
    private static final UUID GITHUB_ACTOR = UUID.nameUUIDFromBytes("archguard:github-webhook".getBytes(StandardCharsets.UTF_8));
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final CurrentActorProvider actors;
    private final GithubRepositoryStore links;
    private final GithubWebhookStore webhooks;
    private final ReportSubmissionStore submissions;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String webhookSecret;

    GithubGovernanceApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            CurrentActorProvider actors, GithubRepositoryStore links, GithubWebhookStore webhooks,
            ReportSubmissionStore submissions,
            AuditRecorder audit, TraceIdProvider traceIds, ObjectMapper mapper, Clock clock,
            @Value("${archguard.github.webhook-secret:}") String webhookSecret) {
        this.projects = projects; this.repositories = repositories; this.actors = actors;
        this.links = links; this.webhooks = webhooks; this.submissions = submissions;
        this.audit = audit; this.traceIds = traceIds;
        this.mapper = mapper; this.clock = clock; this.webhookSecret = webhookSecret;
    }

    @Override @Transactional
    public GithubRepositoryLinkView link(UUID projectId, UUID repositoryId, String externalId,
            String ownerName, String repositoryName) {
        projects.requireMaintainer(projectId);
        repositories.get(projectId, repositoryId);
        String id = SubmissionContract.numericId(externalId, "GitHub repository ID");
        String owner = SubmissionContract.githubName(ownerName, "GitHub owner");
        String name = SubmissionContract.githubName(repositoryName, "GitHub repository name");
        var existing = links.find(projectId, repositoryId);
        if (existing.isPresent()) {
            if (!existing.get().providerRepositoryId().equals(id)) {
                throw new GovernanceConflictException("Repository is linked to a different GitHub identity");
            }
            return existing.get();
        }
        UUID actor = actors.currentActor().id();
        var value = new GithubRepositoryLinkView(projectId, repositoryId, "github", id,
            owner, name, actor, Instant.now(clock));
        if (!links.insert(value)) {
            var raced = links.find(projectId, repositoryId);
            if (raced.isPresent() && raced.get().providerRepositoryId().equals(id)) return raced.get();
            throw new GovernanceConflictException("GitHub repository identity belongs to another repository");
        }
        audit.record(new AuditEvent(actor, projectId, "governance.github.link", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("repositoryId", repositoryId, "providerRepositoryId", id)));
        return value;
    }
    @Override public GithubRepositoryLinkView link(UUID projectId, UUID repositoryId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return links.find(projectId, repositoryId).orElseThrow(GithubLinkNotFoundException::new);
    }
    @Override public GithubPullRequestView pullRequest(UUID projectId, UUID repositoryId, String externalId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return webhooks.pullRequest(projectId, repositoryId,
            SubmissionContract.numericId(externalId, "Pull request ID"))
            .orElseThrow(GithubPullRequestNotFoundException::new);
    }

    @Override @Transactional
    public GithubWebhookResult receive(UUID deliveryId, String eventType, String signature, byte[] payload) {
        if (webhookSecret == null || webhookSecret.isBlank()) throw new GithubWebhookUnavailableException();
        if (payload == null || payload.length == 0 || payload.length > MAX_WEBHOOK_BYTES) {
            throw new InvalidGithubWebhookException("Webhook payload size is invalid");
        }
        verifySignature(signature, payload);
        String digest = SubmissionContract.sha256(payload);
        var existing = webhooks.delivery(deliveryId);
        if (existing.isPresent()) return replay(deliveryId, digest, existing.get());
        if (eventType == null || !eventType.matches("[a-z_]{1,80}")) {
            throw new InvalidGithubWebhookException("Webhook event type is invalid");
        }
        JsonNode root;
        try { root = mapper.readTree(payload); }
        catch (IOException exception) { throw new InvalidGithubWebhookException("Webhook payload is invalid JSON"); }
        String action = root.path("action").asText("");
        boolean pullRequestEvent = "pull_request".equals(eventType) && PR_ACTIONS.contains(action);
        String external = root.path("repository").path("id").asText("");
        GithubRepositoryLinkView link = external.matches("[1-9][0-9]{0,19}")
            ? links.byExternalId(external).orElse(null) : null;
        Instant now = Instant.now(clock);
        GithubPullRequestView pr = null;
        if (pullRequestEvent && link != null) pr = parsePullRequest(root, link);
        boolean staleTime = pr != null && Duration.between(pr.eventAt(), now).abs().compareTo(EVENT_WINDOW) > 0;
        GithubWebhookDisposition disposition = !pullRequestEvent ? GithubWebhookDisposition.IGNORED
            : link == null ? GithubWebhookDisposition.UNLINKED
            : staleTime ? GithubWebhookDisposition.STALE : GithubWebhookDisposition.RECEIVED;
        if (!webhooks.insertDelivery(deliveryId, digest, eventType, action, external,
                link == null ? null : link.projectId(), link == null ? null : link.repositoryId(),
                pr == null ? null : pr.eventAt(), now)) {
            return replay(deliveryId, digest, webhooks.delivery(deliveryId).orElseThrow());
        }
        if (disposition == GithubWebhookDisposition.RECEIVED) {
            disposition = webhooks.applyPullRequest(pr, deliveryId, now)
                ? GithubWebhookDisposition.APPLIED : GithubWebhookDisposition.STALE;
            if (disposition == GithubWebhookDisposition.APPLIED) {
                GithubPullRequestView currentPr = pr;
                submissions.latestCompletedGateForPullRequest(pr.projectId(), pr.repositoryId(),
                    pr.externalId(), pr.headSha()).ifPresent(gateId ->
                    webhooks.attachGateIfCurrent(currentPr.projectId(), currentPr.repositoryId(),
                        currentPr.externalId(), currentPr.headSha(), gateId));
            }
        }
        webhooks.finishDelivery(deliveryId, disposition);
        audit.record(new AuditEvent(GITHUB_ACTOR, link == null ? null : link.projectId(),
            "governance.github.webhook", AuditResult.SUCCESS, traceIds.currentTraceId(),
            Map.of("deliveryId", deliveryId, "eventType", eventType,
                "disposition", disposition.name())));
        return new GithubWebhookResult(deliveryId, disposition, false);
    }

    private GithubPullRequestView parsePullRequest(JsonNode root, GithubRepositoryLinkView link) {
        JsonNode pr = root.path("pull_request");
        String number = SubmissionContract.numericId(root.path("number").asText(""), "Pull request ID");
        String head = SubmissionContract.sha(pr.path("head").path("sha").asText(""), "Pull request head SHA");
        String base = SubmissionContract.sha(pr.path("base").path("sha").asText(""), "Pull request base SHA");
        String branch = GovernanceScopeInput.branch(pr.path("base").path("ref").asText(""));
        Instant eventAt;
        try { eventAt = Instant.parse(pr.path("updated_at").asText("")); }
        catch (RuntimeException exception) { throw new InvalidGovernanceInputException("Pull request event time is invalid"); }
        return new GithubPullRequestView(link.projectId(), link.repositoryId(), number,
            head, base, branch, eventAt, null);
    }
    private GithubWebhookResult replay(UUID id, String digest, GithubWebhookStore.Delivery previous) {
        if (!previous.payloadSha256().equals(digest)) {
            throw new GovernanceConflictException("Webhook delivery ID is bound to different bytes");
        }
        return new GithubWebhookResult(id, previous.disposition(), true);
    }
    private void verifySignature(String signature, byte[] payload) {
        if (signature == null || !signature.matches("sha256=[0-9a-f]{64}")) {
            throw new InvalidGithubWebhookException("Webhook signature is invalid");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload);
            byte[] received = HexFormat.of().parseHex(signature.substring(7));
            if (!MessageDigest.isEqual(expected, received)) {
                throw new InvalidGithubWebhookException("Webhook signature is invalid");
            }
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
