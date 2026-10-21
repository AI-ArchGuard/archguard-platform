# Bounded response metadata compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept only the four owner-approved inert root metadata forms without weakening any other response boundary.

**Architecture:** Extend the existing offline infrastructure codec and fixed rejection enum. Keep HTTP, request projection, accounting, output validation, domain and persistence paths unchanged.

**Tech Stack:** Existing Java 21, Jackson, JUnit 5, AssertJ and Logback; no dependencies.

**Spec:** [Bounded response metadata compatibility](../../technical-design/v0.5-agent-response-metadata-compatibility.md).

## Global Constraints

- `user` and `max_tool_calls`: absent/null only; `top_logprobs`: absent/null/JSON integer zero only.
- `completed_at`: absent/null or integral Unix seconds `0..253402300799`, with equally bounded non-null integral `created_at` and completion >= creation. No new creation requirement when completion absent/null.
- Unknown root/nested fields, tools/session, model, business/citations, usage/fees, duplicate/trailing JSON, request projection, no retry and byte wiping remain strict.
- Offline implementation/CI only; no deployment, provider/credential/account request, fees, database/migration/config changes, stage closure or historical-root-cause claim.

## Review Focus

- Accepted inert metadata must not disable unknown-field rejection or accidentally become request/business output.
- Timestamp conversion must not truncate fractions, overflow, use floating point or introduce a wall-clock dependency.
- Legacy envelopes without completion metadata must not acquire a required creation timestamp.
- Metadata rejection must precede accounting; valid metadata plus invalid business text must preserve trusted usage.
- Fixed diagnostics must not disclose any new field name/value, body, identifier, Key, exception or worker MDC.

## Task 1: Four-field codec compatibility and regression boundary

**Files:** Modify `src/main/java/io/github/aiarchguard/platform/agent/infrastructure/DeepSeekResponsesCodec.java` and `DeepSeekResponseRejection.java`; create `src/test/java/io/github/aiarchguard/platform/agent/infrastructure/DeepSeekResponseMetadataTest.java`; extend `DeepSeekResponseDiagnosticsTest.java`; link the design from `README.md`.

**Interfaces:** Consume unchanged `decodeAccounting(byte[], Set<String>, String)`, `decodeOutput(Accounting)`, `decode(...)` and `DeepSeekLiveResponseClient.send(...)`. Produce no new public interface; one private metadata validator and one private bounded timestamp reader, with fixed `ENVELOPE_METADATA` diagnostic reason.

- [x] Write independent fabricated-envelope parameterized tests: four individual fields and combined acceptance, `(created,completed)` `(0,0)`, `(1,1)`, `(1,2)`, `(253402300799,253402300799)`; assert literal business text and usage `100/20/40/120/0` under both existing price policies.
- [x] Add rejection cases for every forbidden type/value, MAX+1/long overflow/fractions/exponents, missing/null/invalid creation with concrete completion, reverse time, each duplicate key, unknown root alongside approved metadata, and four names in every nested allowlist. Expect unchanged generic no-cause exceptions and correct fixed reasons.
- [x] Extend real-client fake-exchange diagnostics: accepted metadata no event, invalid metadata fixed sanitized event with one exchange/wiped bytes, accepted metadata+invalid business preserves trusted usage. Reuse existing complete logging-channel/trace assertions.
- [x] Run `.\mvnw.cmd -o "-Dtest=DeepSeekResponseMetadataTest,DeepSeekResponseDiagnosticsTest" test`; expected RED from unsupported permitted metadata/new fixed reason, not compilation or environment errors.
- [x] Add exactly four root names; validate null-only/zero-only forms and conditional integral timestamps before usage. Add fixed enum reason, retain all existing code paths and projection.
- [x] Rerun the RED command; expected zero failures/errors/skips. Then run codec/context/client/transport regression classes and full `.\mvnw.cmd -o verify`; expected BUILD SUCCESS, with any platform-specific skips explicitly recorded.
- [x] Review the whole change independently against the five review-focus items; address meaningful findings with RED→GREEN tests. Run whitespace, canonical baseline/license and secret checks; verify no migration/contract/dependency/config or unrelated changes.
- [ ] Commit the tested scope and submit a small `Refs #43` PR; attach it and inspect actual CI results. No deployment/new paid authorization is implied, and #43 remains open. Merge/main-CI handling follows standing integration authority, never a protection change or bypass.

## Actual offline evidence (2026-10-21)

RED: 193 tests, 96 assertion failures, zero errors/skips on unchanged production. GREEN: 193 tests, zero failures/errors/skips. Broader codec/context/client/transport/public-exchange regression: 259 tests, all passing. Full offline Maven verify: BUILD SUCCESS, 916 tests, zero failures/errors, 3 existing Windows-only symlink skips; Linux CI must cover those cases. Independent whole-change review found no actionable Critical/Important/Minor issues. Whitespace, required baseline files, canonical LF license and source/docs secret scans passed. Historical provider root cause, live output compatibility, actual billing and deployment remain unproven; no real call, deployment or stage exit occurred.
