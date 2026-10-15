package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class VersionBoundCredentialUseTest {
    private static final String SYNTHETIC_KEY = "synthetic-use-credential-only";
    private final UUID version = UUID.randomUUID();
    private final LiveResponsePort.Reply validReply = new LiveResponsePort.Reply("{}", null,
        new LiveTokenUsage(100, 20, 0, 120, 0), "synthetic-response", "DeepSeek-V4.1-Flash");

    @Test void preflightRunsBeforeTheVersionBoundSourceAndFinalSendGetsOnlyItsCallbackKey() {
        var order = new ArrayList<String>();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            assertThat(expected).isEqualTo(version);
            order.add("consume");
            callback.accept(SYNTHETIC_KEY);
        });
        var result = use.use(version, () -> order.add("preflight"), key -> {
            assertThat(key).isEqualTo(SYNTHETIC_KEY);
            order.add("send");
            return validReply;
        });
        assertThat(result).isSameAs(validReply);
        assertThat(order).containsExactly("preflight", "consume", "send");
    }

    @Test void preflightFailurePreventsAllCredentialConsumptionAndFinalSend() {
        var consumes = new AtomicInteger();
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            consumes.incrementAndGet();
            callback.accept(SYNTHETIC_KEY);
        });
        assertThatThrownBy(() -> use.use(version, () -> { throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT"); }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(consumes).hasValue(0);
        assertThat(sends).hasValue(0);
    }

    @Test void knownFinalSendFailureSurvivesAConsumerThatSanitizesCallbackExceptions() {
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            try { callback.accept(SYNTHETIC_KEY); }
            catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        });
        for (String code : List.of("MODEL_TIMEOUT", "OUTPUT_INVALID", "MODEL_UNAVAILABLE")) {
            assertThatThrownBy(() -> use.use(version, () -> { }, key -> { throw new LiveResponsePort.CallFailure(code); }))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage(code).hasNoCause();
        }
    }

    @Test void untrustedFinalSendRuntimeExceptionCannotExposeTheCallbackKeyOrCause() {
        var use = new VersionBoundCredentialUse((expected, callback) -> callback.accept(SYNTHETIC_KEY));
        assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
            throw new IllegalStateException(key, new IllegalArgumentException("synthetic secret path"));
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
    }

    @Test void credentialOwnerDenialMapsToAuthorizationRevocationBeforeAnySend() {
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            throw new CredentialFailure(CredentialFailure.Kind.DENIED);
        });
        assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(ProjectPermissionDeniedException.class).hasMessage("Project operation is not permitted").hasNoCause();
        assertThat(sends).hasValue(0);
    }

    @Test void invalidUnavailableOrUntrustedSourceFailureIsSanitizedWithoutAnySend() {
        var sends = new AtomicInteger();
        for (RuntimeException failure : List.of(new CredentialFailure(CredentialFailure.Kind.INVALID),
                new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE), new IllegalStateException(SYNTHETIC_KEY))) {
            var use = new VersionBoundCredentialUse((expected, callback) -> { throw failure; });
            assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
                sends.incrementAndGet();
                return validReply;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
        assertThat(sends).hasValue(0);
    }

    @Test void sourceFailureAfterAReplyRejectsBusinessOutputButRetainsBillableUsageAndTraceIds() {
        for (RuntimeException failure : List.of(new CredentialFailure(CredentialFailure.Kind.INVALID),
                new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE), new CredentialFailure(CredentialFailure.Kind.DENIED),
                new IllegalStateException(SYNTHETIC_KEY, new IllegalArgumentException("synthetic secret path")))) {
            var sends = new AtomicInteger();
            var use = new VersionBoundCredentialUse((expected, callback) -> {
                callback.accept(SYNTHETIC_KEY);
                throw failure;
            });
            var result = use.use(version, () -> { }, key -> {
                sends.incrementAndGet();
                return validReply;
            });
            assertBillableOutputRejected(result);
            assertThat(sends).hasValue(1);
        }
    }

    @Test void repeatedSourceCallbackRejectsBusinessOutputRetainsUsageAndCanNeverSendTwice() {
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            callback.accept(SYNTHETIC_KEY);
            callback.accept(SYNTHETIC_KEY);
        });
        var result = use.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        });
        // Throwing here would discard a completed provider reply and incorrectly turn known charges into UNKNOWN.
        assertBillableOutputRejected(result);
        assertThat(sends).hasValue(1);
    }

    @Test void missingCallbackAndNullReplyFailClosed() {
        var sends = new AtomicInteger();
        var noCallback = new VersionBoundCredentialUse((expected, callback) -> { });
        assertThatThrownBy(() -> noCallback.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(sends).hasValue(0);
        var nullReply = new VersionBoundCredentialUse((expected, callback) -> callback.accept(SYNTHETIC_KEY));
        assertThatThrownBy(() -> nullReply.use(version, () -> { }, key -> null))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
    }

    @Test void aRetainedCallbackCannotSendAfterTheSynchronousCredentialUseHasEnded() {
        var sends = new AtomicInteger();
        var retained = new AtomicReference<Consumer<String>>();
        var use = new VersionBoundCredentialUse((expected, callback) -> retained.set(callback));
        assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(sends).hasValue(0);
        Consumer<String> lateCallback = retained.getAndSet(null);
        assertThat(lateCallback).isNotNull();
        lateCallback.accept(SYNTHETIC_KEY);
        assertThat(sends).as("The closed credential callback cannot send later").hasValue(0);
    }

    @Test void aDifferentThreadCannotSendEvenWhileTheSynchronousCredentialUseIsActive() {
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            Thread otherThread = new Thread(() -> callback.accept(SYNTHETIC_KEY), "synthetic-credential-callback");
            otherThread.start();
            try { otherThread.join(2_000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Synthetic callback thread join interrupted", interrupted);
            }
            assertThat(otherThread.isAlive()).as("Synthetic callback thread completes within two seconds").isFalse();
        });
        assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(sends).as("Credential final send is confined to the calling thread").hasValue(0);
    }

    @Test void anInvalidThreadCallbackPermanentlyRejectsALaterSynchronousCallbackWithoutSending() {
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            Thread otherThread = new Thread(() -> callback.accept(SYNTHETIC_KEY), "synthetic-invalid-callback-first");
            otherThread.start();
            try { otherThread.join(2_000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Synthetic callback thread join interrupted", interrupted);
            }
            assertThat(otherThread.isAlive()).as("Rejected callback thread completes within two seconds").isFalse();
            callback.accept(SYNTHETIC_KEY);
        });
        assertThatThrownBy(() -> use.use(version, () -> { }, key -> {
            sends.incrementAndGet();
            return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(sends).as("A rejected callback cannot be repaired by trying again on the calling thread").hasValue(0);
    }

    @Test void missingRequiredArgumentsCannotRunPreflightOrConsume() {
        var consumes = new AtomicInteger();
        var preflights = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> consumes.incrementAndGet());
        assertThatThrownBy(() -> use.use(null, preflights::incrementAndGet, key -> validReply))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> use.use(version, null, key -> validReply))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> use.use(version, preflights::incrementAndGet, null))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(preflights).hasValue(0);
        assertThat(consumes).hasValue(0);
    }

    @Test void boundedUseForwardsTheIdenticalOriginalBudgetAfterPreflightWithoutReset() {
        var order = new ArrayList<String>();
        var budget = new AtomicLong(Duration.ofSeconds(30).toNanos());
        LongSupplier remaining = budget::get;
        var use = new VersionBoundCredentialUse(boundedSource((received, callback) -> {
            assertThat(received).isSameAs(remaining);
            assertThat(received.getAsLong()).isEqualTo(Duration.ofSeconds(20).toNanos());
            order.add("consume"); budget.set(Duration.ofSeconds(5).toNanos());
            callback.accept(SYNTHETIC_KEY);
        }));
        var result = use.use(version, remaining, () -> {
            order.add("preflight"); budget.set(Duration.ofSeconds(20).toNanos());
        }, key -> {
            assertThat(key).isEqualTo(SYNTHETIC_KEY);
            assertThat(remaining.getAsLong()).isEqualTo(Duration.ofSeconds(5).toNanos());
            order.add("send"); return validReply;
        });
        assertThat(result).isSameAs(validReply);
        assertThat(order).containsExactly("preflight", "consume", "send");
    }

    @Test void boundedUseCannotFallbackToALegacySourceEvenWithAConstantPositiveBudget() {
        var legacy = new AtomicInteger(); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse((expected, callback) -> {
            legacy.incrementAndGet(); callback.accept(SYNTHETIC_KEY);
        });
        assertThatThrownBy(() -> use.use(version, () -> Duration.ofSeconds(30).toNanos(), () -> {}, key -> {
            sends.incrementAndGet(); return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(legacy).hasValue(0); assertThat(sends).hasValue(0);
    }

    @Test void invalidExpiredOrThrowingBudgetCannotPreflightConsumeOrSend() {
        var preflights = new AtomicInteger(); var consumes = new AtomicInteger(); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            consumes.incrementAndGet(); callback.accept(SYNTHETIC_KEY);
        }));
        for (LongSupplier remaining : List.<LongSupplier>of(() -> 0, () -> -1)) {
            assertThatThrownBy(() -> use.use(version, remaining, preflights::incrementAndGet, key -> {
                sends.incrementAndGet(); return validReply;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        }
        for (LongSupplier remaining : List.<LongSupplier>of(() -> Long.MAX_VALUE,
                () -> { throw new IllegalStateException(SYNTHETIC_KEY, new IllegalArgumentException("private clock detail")); })) {
            assertThatThrownBy(() -> use.use(version, remaining, preflights::incrementAndGet, key -> {
                sends.incrementAndGet(); return validReply;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
        assertThat(preflights).hasValue(0); assertThat(consumes).hasValue(0); assertThat(sends).hasValue(0);
    }

    @Test void expiryDuringPreflightPreventsAllCredentialConsumption() {
        var budget = new AtomicLong(10_000); var consumes = new AtomicInteger(); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            consumes.incrementAndGet(); callback.accept(SYNTHETIC_KEY);
        }));
        assertThatThrownBy(() -> use.use(version, budget::get, () -> budget.set(0), key -> {
            sends.incrementAndGet(); return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(consumes).hasValue(0); assertThat(sends).hasValue(0);
    }

    @Test void expiryDuringSourceWaitCannotReachFinalSendEvenIfSourceInvokesCallback() {
        var budget = new AtomicLong(10_000); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            budget.set(0); callback.accept(SYNTHETIC_KEY);
        }));
        assertThatThrownBy(() -> use.use(version, budget::get, () -> {}, key -> {
            sends.incrementAndGet(); return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(sends).hasValue(0);
    }

    @Test void timedOutSourceFailureIsSanitizedAndDoesNotSend() {
        var budget = new AtomicLong(10_000); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            budget.set(0); throw new IllegalStateException(SYNTHETIC_KEY, new IllegalArgumentException("private source detail"));
        }));
        assertThatThrownBy(() -> use.use(version, budget::get, () -> {}, key -> {
            sends.incrementAndGet(); return validReply;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(sends).hasValue(0);
    }

    @Test void supplierThatInterruptsItsOwnCallerCannotReachPreflightOrSend() {
        var preflights = new AtomicInteger(); var consumes = new AtomicInteger(); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            consumes.incrementAndGet(); callback.accept(SYNTHETIC_KEY);
        }));
        try {
            assertThatThrownBy(() -> use.use(version, () -> {
                Thread.currentThread().interrupt(); return 10_000;
            }, preflights::incrementAndGet, key -> {
                sends.incrementAndGet(); return validReply;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(preflights).hasValue(0); assertThat(consumes).hasValue(0); assertThat(sends).hasValue(0);
    }

    @Test void interruptedSourceFailurePreservesTheFlagAndCannotSend() {
        var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            Thread.currentThread().interrupt(); throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        }));
        try {
            assertThatThrownBy(() -> use.use(version, () -> 10_000, () -> {}, key -> {
                sends.incrementAndGet(); return validReply;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(sends).hasValue(0);
    }

    @Test void replySurvivesLaterSourceTimeoutAndPrivateCleanupFailure() {
        var budget = new AtomicLong(10_000); var sends = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
            callback.accept(SYNTHETIC_KEY); budget.set(0);
            throw new IllegalStateException(SYNTHETIC_KEY, new IllegalArgumentException("private cleanup detail"));
        }));
        var result = use.use(version, budget::get, () -> {}, key -> {
            sends.incrementAndGet(); return validReply;
        });
        assertBillableOutputRejected(result); assertThat(sends).hasValue(1);
    }

    @Test void replySurvivesAnExpiredOrBrokenClockAfterSuccessfulSourceReturn() {
        for (boolean broken : List.of(false, true)) {
            var returned = new java.util.concurrent.atomic.AtomicBoolean();
            var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> {
                callback.accept(SYNTHETIC_KEY); returned.set(true);
            }));
            var result = use.use(version, () -> {
                if (returned.get()) {
                    if (broken) throw new IllegalStateException(SYNTHETIC_KEY);
                    return 0;
                }
                return 10_000;
            }, () -> {}, key -> validReply);
            assertBillableOutputRejected(result);
        }
    }

    @Test void boundedMissingArgumentsCannotRunPreflightOrConsume() {
        var preflights = new AtomicInteger(); var consumes = new AtomicInteger();
        var use = new VersionBoundCredentialUse(boundedSource((remaining, callback) -> consumes.incrementAndGet()));
        assertThatThrownBy(() -> use.use(version, null, preflights::incrementAndGet, key -> validReply))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> use.use(null, () -> 10_000, preflights::incrementAndGet, key -> validReply))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> use.use(version, () -> 10_000, null, key -> validReply))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> use.use(version, () -> 10_000, preflights::incrementAndGet, null))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(preflights).hasValue(0); assertThat(consumes).hasValue(0);
    }

    private CredentialUseSource boundedSource(BiConsumer<LongSupplier, Consumer<String>> operation) {
        return new CredentialUseSource() {
            @Override public void consume(UUID expected, Consumer<String> callback) {
                throw new AssertionError("Bounded use must never select the unbounded source");
            }
            @Override public void consume(UUID expected, LongSupplier remaining, Consumer<String> callback) {
                assertThat(expected).isEqualTo(version);
                operation.accept(remaining, callback);
            }
        };
    }

    private void assertBillableOutputRejected(LiveResponsePort.Reply result) {
        assertThat(result).isNotSameAs(validReply);
        assertThat(result.rawJson()).isNull();
        assertThat(result.outputFailureCode()).isEqualTo("OUTPUT_INVALID");
        assertThat(result.usage()).isEqualTo(validReply.usage());
        assertThat(result.providerResponseId()).isEqualTo(validReply.providerResponseId());
        assertThat(result.actualModelId()).isEqualTo(validReply.actualModelId());
        assertThat(result.toString()).doesNotContain(SYNTHETIC_KEY, "synthetic secret path");
    }
}
