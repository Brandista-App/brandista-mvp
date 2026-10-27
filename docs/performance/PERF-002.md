# PERF-002: authentication latency and connection contention

## Scope and change

Continues merged PERF-001 PR #30 from main `2a1fa32`. Baseline instrumentation is `4068298db6741727a4ce56429c32c03d87b00ff9`; implementation is `b2a491d70b75a6f94e601671a4940541f37ddd03`. The protected-request authority converter now reads current account status, role and non-revoked super-admin grant in one scalar database statement. It removes entity hydration, the second grant query and the surrounding multi-statement read transaction. Every bearer request still reads current database state. No authorization cache, JWT trust shortcut, BCrypt work-factor change, Hikari increase, inventory/write-path change or search contract change is introduced.

Focused regression coverage verifies one statement without entity hydration, grant revocation, role/status changes, missing accounts, malformed subjects before queries, and BCrypt strength 10. Existing login and privileged identity tests pass. Full Maven verification on the implementation: 201 discovered, 200 passed, one opt-in load test skipped, zero failures/errors (`target/perf002-verify.log`).

## Measurement

The existing disposable MySQL runner and unchanged mixed-load generator retain five-second ramp, 30-second hold, 500-ms think time, six-second timeout and unchanged escalation/resource gates. MySQL 8.4.11: one CPU, 640 MiB, 128-MiB buffer pool; JVM heap 512 MiB; Hikari maximum 10. Synthetic fixture: 1,002 products and 500 load accounts. Each run creates a loopback-only database with ephemeral credentials and checks stock, orders, idempotency and restore correctness.

Test-only probes observe Tomcat dispatch queue, user lookup, connection acquisition/hold, BCrypt, current-account lookup, JWT encoding and JSON converter writes. Wall and current-thread CPU times are distinguished where available. Dispatch timing starts at observed executor submission; it excludes network time and earlier socket accept/poller delay. JSON timing includes converter output writes/backpressure. Nested timings must not be added, and independent percentiles must not be subtracted. Probe overhead and shared Windows/Docker host contention affect these local results.

## Saved baseline

| Users | Requests/s | Overall p95 ms | Login p50 ms | Login p95 ms | Login p99 ms | HTTP error rate |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 25.39 | 543.21 | 329.68 | 789.70 | 789.70 | 0% |
| 50 | 44.71 | 1893.40 | 867.16 | 2031.45 | 2419.59 | 0% |
| 100 | 50.06 | 3259.55 | 3063.47 | 4410.05 | 5381.99 | 1.016% |

The original accepted PERF-001 result at 50 users had login p95 3163.25 ms. This later baseline passed at 2031.45 ms: host/run variation is material, and the earlier failure cannot be represented as deterministic. The 100-user baseline failed and stopped escalation. BCrypt held no database connection at entry. The baseline current-account check held a connection for about 145 ms on average across two queries/read transaction at 50 users, supporting the minimal round-trip reduction.

## Post-change run

Docker was initially stopped; the failed startup generated no accepted measurements. Starting Docker also restored existing workspace containers, so shared-host conditions limit comparison with the saved baseline. Completed results below fail the 50-user acceptance gates. Higher post-change stages and overall performance improvement remain NOT VERIFIED. Local closed-loop measurements do not establish commercial concurrency capacity.

## Observed post-change results (2026-10-27)

| Users | Requests/s | Overall p95 ms | Login p50 ms | Login p95 ms | Login p99 ms | HTTP error rate | Outcome |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 22.00 | 822.95 | 161.53 | 2080.99 | 2080.99 | 0% | completed |
| 50 | 26.97 | 3042.28 | 1388.80 | 2403.50 | 2718.25 | 5.654% | aborted; latency/error gates FAIL |
| 100/250/500 | — | — | — | — | — | — | NOT VERIFIED |

All 50 login requests succeeded. The 50-user stage completed 566 requests in 20.983 seconds before abort, versus 1,742 in 38.966 seconds in the baseline. The requested workload and gates were identical, but the aborted stage did not receive a full hold. Errors included pool-acquisition 503s and four 500s across write/merchant operations. These remain failures, not ignored samples. No end-to-end improvement or passing 50-user capacity is claimed.

The after-run environment records the implementation SHA plus modifications only to the offline summarizer and evidence directory. The full report was drafted after application launch. None changes the measured application, probes or generator. Docker startup was unusually slow and restored three existing workspace containers. Minimum sampled host free memory at 50 users was 286.9 MB after versus 513.4 MB before. This observational comparison cannot attribute aggregate differences exclusively to the patch; a controlled repeated paired run on a quiet host remains necessary. No unrelated workspace containers were stopped.

## Stage and resource evidence

At 50 users, per-request login stage wall p95 (baseline / after, ms): observed dispatch queue 93.00 / 188.77; user-details including its database acquisition 1139.66 / 883.69; summed connection acquisition 1797.73 / 1977.08; BCrypt 285.10 / 334.44; JWT 1.42 / 1.67; JSON writing 0.98 / 1.12. These overlap and are not additive. Full invocation counts, p50/p95/p99, CPU and wall timings, user repository reads and post-password account/grant stages are in the auth summaries and compressed request traces.

BCrypt mean wall/CPU was 217.45/126.25 ms before and 236.79/118.44 ms after. Zero held connections at BCrypt entry in both runs rules out password work retaining the pool slot. CPU granularity is approximately 15.625 ms on this host; zeros for tiny operations do not mean free work. The synchronized login burst at stage entry is materially different from steady-state arrivals. Pool waiting dominates many request wall times; BCrypt CPU and scheduling contribute, while JWT/serialization are small. At 50 users, the native executor sampled 50 threads, 49/50 peak busy before/after and no queued tasks. This does not measure pre-executor socket waiting. The baseline 100-user stage reached the configured 64-thread limit and pool saturation.

| 50-user observation | Baseline | After |
| --- | ---: | ---: |
| Hikari peak active / max | 10 / 10 | 10 / 10 |
| Peak pending | 37 | 37 |
| Stage-delta mean acquisition ms | 511.88 | 639.12 |
| Stage-delta mean connection hold ms | 173.13 | 252.57 |
| Process CPU gauge peak (fraction) | 0.510 | 0.568 |
| Row-lock waits added | 40 | 10 |
| Row-lock time added ms | 8183 | 2466 |
| Current-account connection hold mean ms | 144.63 | 62.18 |
| Current-account connection hold p95 ms | 307.71 | 117.01 |

Current-account hold observations have different sample counts (306 before, 110 successful holds after); the reduction is consistent with fewer round trips but is not a controlled causal estimate. The focused statement-count regression supplies deterministic evidence of two reads becoming one. Public search retains its transaction across page/count/materialization: observed hold mean was 186.34 / 357.41 ms and acquisition mean 570.26 / 745.73 ms before/after. Search and other request work still compete for the same ten connections. No additional transaction/write-path changes or pool tuning are justified by this noisy run.

Stage-delta pool means subtract cumulative count/time snapshots; boundary probes are included. SQL lock counters are stage deltas, not sampled instantaneous locks. Raw MySQL samples retain CPU, memory, lock-wait edges, active transaction count and oldest transaction age. Backend RSS remains unavailable; JVM used memory is not RSS. Different stage durations make raw lock counts unsuitable as direct rate comparisons.

## Correctness and review status

Both runs passed stock reconciliation, customer/idempotency uniqueness, fulfillment-brand correspondence and unpaid COD financial totals. The after run ended with 25 orders, 50 lines and 50 fulfillments, including the commercial fixture. Consistent dump/new-schema restore passed six table-count comparisons in 11.912 seconds. The synthetic dump is excluded from committed evidence. The disposable database and sampler were removed.

Sanitized evidence is under [perf-002](evidence/perf-002/): environments, exact route/status/sample data, SQL snapshots/plans, request timing traces, thread samples, integrity and restore results. The standalone summarizer supports gzip inputs and restricts executor samples to the load observation window. No tokens, passwords, email identities or request bodies are captured by the timing probes.

This is a reviewable minimal optimization with passing security/correctness regression tests and **failed end-to-end performance acceptance**. Keep the PR in draft pending controlled paired reproduction and investigation of remaining pool/error bottlenecks. Do not merge or deploy automatically. No commercial concurrency capacity has been established.
