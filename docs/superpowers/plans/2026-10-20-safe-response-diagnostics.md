# Safe response diagnostics implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Distinguish existing rejected-response paths using fixed internal diagnostics without new outbound calls or weakened validation.

**Architecture:** Keep typed, body-free reasons in the infrastructure package and log only at the synchronous response-client boundary. Preserve public exceptions/results and known-versus-unknown accounting.

**Tech Stack:** Existing Java21, Jackson, SLF4J/Logback, JUnit/AssertJ; no new dependency.

**Spec:** `../../technical-design/v0.5-agent-safe-response-diagnostics.md` (approved narrow offline scope; implementation choices belong to the agent).

## Global Constraints

- No new real request/fee/Key or account probe/deployment; current closed runtime is not test input.
- No API/Schema/migration/model/protocol/limits/retry changes; no raw values, parser causes or bodies in diagnostics.
- Known usage stays known on business rejection; UNKNOWN reservation is never released by this work.
- Preserve original dirty root checkout; use clean linked903ddc5 worktree branch only.

## Review Focus

- Async body-limit reason must reach caller-thread trace rather than log from HTTP callbacks.
- Unknown JSON keys and escaped credentials must never become diagnostic values or throwable objects.
- Duplicate/trailing JSON must stay rejected and successful replies must not acquire warning events.
- Business rejection must preserve trusted usage and byte wiping; diagnosis must not change UNKNOWN/settlement rules.
- One rejection produces one event, no extra exchange; unrelated MDC is not forwarded by request scope.

### Task 1: One coupled response-diagnostics increment

**Files:** create infrastructure `DeepSeekResponseRejection.java` and `DeepSeekResponseDiagnosticsTest.java`; modify existing `DeepSeekResponsesCodec`, `DeepSeekTransportFailure`, `DeepSeekHttpTransport`, `BoundedJdkHttpExchange`, `DeepSeekLiveResponseClient`; add the technical-design note and README link.

**Interfaces:** no public signature changes. Package-private typed reason only; existing client prepare/send and public failure codes remain. Emit event `agent.provider.response.rejected` with fixed `stage`/`reason`, inherited isolated trace; no Throwable log argument.

- [x] Write offline tests against existing client/logging APIs first: boundary/envelope/accounting/business classes, private sentinels in every exposed diagnostic channel, one exchange, wiped returned byte arrays, known usage preserved, trace isolation, success silence.
- [x] Run `./mvnw.cmd -o -Dtest=DeepSeekResponseDiagnosticsTest test`; expect assertion failures because old implementation emits no diagnostics, not compilation errors.
- [x] Add package-private fixed reason/exception and preserve it through existing catches; emit once at synchronous client boundary. Preserve all rejection predicates and limits.
- [x] Run new diagnostics tests plus existing transport/codec/client/core tests; require zero failures and unchanged one-call/usage/privacy assertions.
- [x] Add real loopback oversized-body classification coverage with existing bounded JDK exchange; no production endpoint override.
- [x] Run full `./mvnw.cmd -o verify`, diff/secret/license checks and fresh independent whole-change review. Record limitations and any warnings rather than lowering assertions.
- [ ] Commit exact tested tree, open a small reviewable PR under standing repository workflow authorization, confirm PR CI; do not auto-close #43 or deploy/call real service. Confirm integration authority before any materially different action.

## Local verification, 2026-10-20

- RED: 80 tests, 79 missing-diagnostic assertion failures, no compilation/test errors; success case passed.
- GREEN: all 80 diagnostics tests passed; focused transport/codec/client/core regression: 167 tests passed.
- Full offline Maven verify: 803 tests, no failures/errors, three existing Linux-only symlink guards skipped on Windows; JAR packaging passed. Linux CI is a separate pending gate.
- Diff, secret scans, required files and canonical license checksum passed; no dependency, workflow, public contract or migration changes. Independent source review found no actionable defects.
- Remaining nonblocking coverage gaps: event-object assertions do not directly exercise production Logstash JSON serialization; non-`OUTPUT_INVALID` transport-event silence is not a dedicated test. No claim of actual provider acceptance, historical root cause, current runtime health or stage completion.
