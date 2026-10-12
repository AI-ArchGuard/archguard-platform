package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.identity.CurrentActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class StoredCredentialUseSourceTest {
    private static final String SYNTHETIC_KEY = "synthetic-use-credential-only";
    @TempDir Path root;

    @Test void consumesCurrentVersionAfterVersionOnlyIntentAuditWithoutCompletionAudit() throws Exception {
        var fixture = fixture();
        var received = new AtomicReference<String>();
        fixture.source.consume(fixture.version, key -> {
            fixture.inCallback.set(true);
            try {
                assertThat(fixture.audits).hasSize(1);
                received.set(key);
                // A completion audit here would enter another module under the store lock and lose a valid reply.
                fixture.failAudit.set(true);
            } finally { fixture.inCallback.remove(); }
        });
        assertThat(received.get()).isEqualTo(SYNTHETIC_KEY);
        assertThat(fixture.audits).containsExactly(new AuditEvent(fixture.owner, null,
            "AGENT_CREDENTIAL_USE_REQUESTED", AuditResult.SUCCESS, "a".repeat(32),
            Map.of("provider", "DEEPSEEK", "credentialVersion", fixture.version.toString())));
        assertThat(fixture.audits.toString()).doesNotContain(SYNTHETIC_KEY, root.toString());
    }

    @Test void replacingOrDeletingTheSharedSlotInvalidatesTheOldVersion() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        var replacement = fixture.service.write("synthetic-replacement-only-key");
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> calls.incrementAndGet()))
            .isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
        fixture.source.consume(replacement.credentialVersion(), key -> {
            assertThat(key).isEqualTo("synthetic-replacement-only-key");
            calls.incrementAndGet();
        });
        fixture.service.delete();
        assertThatThrownBy(() -> fixture.source.consume(replacement.credentialVersion(), key -> calls.incrementAndGet()))
            .hasMessage("credential.unavailable").hasNoCause();
        assertThat(calls).hasValue(1);
        assertThat(new StoredCredentialVersionSource(fixture.service).current().configured()).isFalse();
    }

    @Test void projectMaintainerCannotConsumeTheConfiguredOwnersKey() throws Exception {
        var fixture = fixture();
        fixture.actor.set(UUID.randomUUID());
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> calls.incrementAndGet()))
            .isInstanceOf(CredentialFailure.class).hasMessage("credential.denied").hasNoCause();
        assertThat(calls).hasValue(0);
        assertThat(fixture.audits).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("AGENT_CREDENTIAL_AUTHORIZE");
            assertThat(event.result()).isEqualTo(AuditResult.DENIED);
            assertThat(event.metadata()).containsExactlyEntriesOf(Map.of("provider", "DEEPSEEK"));
        });
    }

    @Test void disabledManagementAndNonLocalProfileCannotConsume() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        fixture.environment.setProperty("archguard.agent.credentials.enabled", "false");
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> calls.incrementAndGet()))
            .hasMessage("credential.unavailable").hasNoCause();
        fixture.environment.setProperty("archguard.agent.credentials.enabled", "true");
        fixture.environment.setActiveProfiles("oidc");
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> calls.incrementAndGet()))
            .hasMessage("credential.unavailable").hasNoCause();
        assertThat(calls).hasValue(0);
        assertThat(fixture.audits).isEmpty();
    }

    @Test void failedIntentAuditPreventsCallbackAndLeavesTheStoredVersionUnchanged() throws Exception {
        var fixture = fixture();
        var calls = new AtomicInteger();
        fixture.failAudit.set(true);
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> calls.incrementAndGet()))
            .hasMessage("credential.unavailable").hasNoCause();
        assertThat(calls).hasValue(0);
        fixture.failAudit.set(false);
        assertThat(new StoredCredentialVersionSource(fixture.service).current().credentialVersion()).isEqualTo(fixture.version);
        assertThat(fixture.audits).isEmpty();
    }

    @Test void reentrantServiceAndMetadataCallsAreRejectedBeforeIdentityOrAuditAccess() throws Exception {
        var fixture = fixture();
        var metadata = new StoredCredentialVersionSource(fixture.service);
        fixture.source.consume(fixture.version, key -> {
            fixture.inCallback.set(true);
            try {
                int actorReads = fixture.actorReads.get();
                int auditCount = fixture.audits.size();
                List<Runnable> forbidden = List.of(
                    () -> fixture.service.status(),
                    () -> fixture.service.write("synthetic-reentrant-only-key"),
                    () -> fixture.service.delete(),
                    () -> fixture.service.authorize("http://localhost:8082", false, true),
                    () -> fixture.service.rejectInvalidWrite(),
                    () -> metadata.current(),
                    () -> fixture.source.consume(fixture.version, nested -> { throw new AssertionError("Nested consume ran"); }));
                for (Runnable operation : forbidden) {
                    assertThatThrownBy(operation::run).isInstanceOf(CredentialFailure.class)
                        .hasMessage("credential.unavailable").hasNoCause();
                    assertThat(fixture.actorReads).hasValue(actorReads);
                    assertThat(fixture.audits).hasSize(auditCount);
                }
                assertThat(key).isEqualTo(SYNTHETIC_KEY);
            } finally { fixture.inCallback.remove(); }
        });
        assertThat(metadata.current().credentialVersion()).isEqualTo(fixture.version);
        fixture.source.consume(fixture.version, key -> assertThat(key).isEqualTo(SYNTHETIC_KEY));
        assertThat(fixture.audits).hasSize(2);
    }

    @Test void callbackErrorDoesNotLeakItsMessageOrCauseAndDoesNotPoisonTheConsumer() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, key -> {
            throw new IllegalStateException(key + " " + root, new IllegalArgumentException(key));
        })).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
        fixture.source.consume(fixture.version, key -> assertThat(key).isEqualTo(SYNTHETIC_KEY));
        assertThat(fixture.audits.toString()).doesNotContain(SYNTHETIC_KEY, root.toString());
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(root.resolve("encrypted"));
        PrivateCredentialFiles.restrict(directory, true);
        Path master = Files.write(root.resolve("synthetic-master"), new byte[32]);
        PrivateCredentialFiles.restrict(master, false);
        return new Fixture(directory, master);
    }

    private static final class Fixture {
        final UUID owner = UUID.randomUUID();
        final AtomicReference<UUID> actor = new AtomicReference<>(owner);
        final AtomicInteger actorReads = new AtomicInteger();
        final AtomicBoolean failAudit = new AtomicBoolean();
        final ThreadLocal<Boolean> inCallback = new ThreadLocal<>();
        final ArrayList<AuditEvent> audits = new ArrayList<>();
        final MockEnvironment environment = new MockEnvironment();
        final CredentialApplicationService service;
        final StoredCredentialUseSource source;
        final UUID version;

        Fixture(Path directory, Path master) {
            environment.setActiveProfiles("local-compose");
            environment.withProperty("archguard.agent.credentials.enabled", "true")
                .withProperty("archguard.agent.credentials.owner-id", owner.toString())
                .withProperty("archguard.agent.credentials.origin", "http://localhost:8082")
                .withProperty("archguard.agent.credentials.allow-loopback-http", "true")
                .withProperty("archguard.agent.credentials.directory", directory.toString())
                .withProperty("archguard.agent.credentials.master-file", master.toString())
                .withProperty("archguard.agent.credentials.deployment-id", UUID.randomUUID().toString());
            service = new CredentialApplicationService(environment, () -> {
                actorReads.incrementAndGet();
                return new CurrentActor(actor.get(), Set.of("project:maintain"));
            }, event -> {
                assertThat(Boolean.TRUE.equals(inCallback.get())).as("No audit under the credential callback lock").isFalse();
                if (failAudit.get()) throw new IllegalStateException("synthetic rejected audit");
                audits.add(event);
            }, () -> "a".repeat(32), Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
            version = service.write(SYNTHETIC_KEY).credentialVersion();
            audits.clear();
            actorReads.set(0);
            source = new StoredCredentialUseSource(service);
        }
    }
}
