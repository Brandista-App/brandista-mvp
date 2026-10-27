# PERF-002: final repair and controlled comparison

## Decision

The authorization optimization and fail-closed availability repair are technically validated and recommended for merge as a bounded improvement. **Two sequential post-change 50-user runs passed every original gate with zero HTTP errors.** Full local verification passed 202 tests (203 discovered, one opt-in load test skipped), and hosted CI passed on measured commit [26b2dbf](https://github.com/Brandista-App/brandista-mvp/commit/26b2dbf696095906192ef54e04dcfb85aef83bc8). Final documentation-head CI is attached to [existing PR #31](https://github.com/Brandista-App/brandista-mvp/pull/31).

**100 users remain NOT VERIFIED.** The attempt capped at 100 stopped at 10 users when free host memory fell below the unchanged safety floor. There was no post-repair 100-user stage, and no 250/500-user attempt. These short local closed-loop results establish no commercial concurrency capacity, production SLO or sustained arrival-rate capacity. Nothing is merged or deployed automatically.

## Exact failure attribution and repair

The earlier failed run at implementation b2a491d70b75a6f94e601671a4940541f37ddd03 had **32 errors / 566 requests = 5.6537%**, not failed logins. All 50 login requests succeeded. Client route/status counts and sanitized server-stack attribution are in [failed-run-attribution.json](evidence/perf-002/failed-run-attribution.json).

- **28 HTTP503s:** controller-side database/transaction resource failures. The historical log contains exactly 32 Hibernate connection-acquisition timeout ERROR entries, matching the 32 failed responses. There were no other servlet error root causes. The old run lacked request IDs, so a one-to-one mapping of every 503 to a particular server log line cannot be reconstructed.
- **Four HTTP500s:** cart-clear, cart-add, merchant-orders and merchant-order-detail had one each. All four servlet stack traces show DataAccessResourceFailureException -> JDBCConnectionException -> SQLTransientConnectionException in CurrentAccountAuthorities.convert / findCurrentAuthorizationById, called by BearerTokenAuthenticationFilter. They timed out after 3002, 3036, 3020 and 3000 ms while the pool was full. MVC advice cannot intercept this earlier authentication stage.

CurrentAccountAuthorities now translates only database-resource/transaction-acquisition failures into CurrentAccountUnavailableException, a dedicated AuthenticationException. Spring Security's bearer failure path clears authentication and invokes the existing entry point, which emits the existing sanitized error envelope with 503. Access remains denied. Invalid JWTs/accounts retain 401; missing grants/roles retain authorization denial. Other programming exceptions are not relabeled as unavailable.

This response correction **does not remove any benchmark error**: 503 remains a failure under the unchanged gates. Integration tests inject both resource and transaction failure, prove sanitized 503 responses, verify recovery performs another fresh lookup and still denies a customer the privileged endpoint, and verify invalid JWTs return 401 before a database lookup.

The previous account/grant optimization is preserved: one uncached scalar statement reads current role, status and non-revoked grant instead of hydrating the user plus a separate grant query inside a broad read transaction. Unique user/grant constraints bound the join. Every bearer request still reads current database state; JWT claims cannot grant authority. Existing one-query, revocation, malformed-subject, missing-account and BCrypt regression checks remain.

## Controlled method and provenance

Measurements were sequential in **baseline-1, after-1, baseline-2, after-2** order on 2026-10-27, with no overlapping test suites or load runs. The three auto-restored loopback development containers were stopped for all five measurements and restored afterward. Each environment records no other running Docker containers. Windows background activity, filesystem caches and WSL memory still varied; this is a better-controlled local comparison, not a randomized experiment on dedicated hardware.

Baseline application: **4068298db6741727a4ce56429c32c03d87b00ff9**, preserving merged PERF-001 from main 2a1fa32. Its isolated control checkout has only the same three test/runner modifications copied from the repair: class-only failure/operation diagnostics, environment capture and a stage cap. No production baseline code was changed. After application: clean **26b2dbf696095906192ef54e04dcfb85aef83bc8** for every run, including the resource-aborted attempt.

All five environments record the identical workload blob **12df7cc6a2e60f8c2e8c5f2f6364e065b04ae94e** and probe blob **597a3340c7e8ca24ffb2599612d3130780940836**. Original mixed-load routes, think times, timeouts and gates are unchanged. The optional MaximumUsers cap prevents unwanted escalation; it does not alter a stage or its gates. Reproduce from each checkout with Java 21 and:

~~~powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
./scripts/load/run-isolated.ps1 -HoldSeconds 30 -MaximumUsers 50
# Only after repeated 50-user success and sufficient resources:
./scripts/load/run-isolated.ps1 -HoldSeconds 30 -MaximumUsers 100
~~~

Fixed configuration: Node 24.21.0; Java 21.0.12.1; MySQL 8.4.11 image sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70; four host logical CPUs / 8,489,750,528 bytes RAM; database one CPU / 640 MiB / 128-MiB buffer pool; JVM heap 512 MiB; Hikari maximum **10**, acquisition timeout 3 seconds; native HTTP thread maximum 64. Performance schema and 100-ms slow-query TABLE logging remain enabled. Synthetic fixture: 1,002 products, 500 load accounts, two brands, one category; shared checkout SKUs preserve stock contention. No production database or external load target is used.

Stages use five-second ramp, 30-second hold, 500-ms think time, six-second HTTP timeout and five-second cooldown. Requests/s divides completed requests by actual ramp/hold/drain duration. Original gates remain: resource/integrity aborts, error-rate abort above 5% after 100 requests, three monitoring failures, heap-used >600 MiB, free host memory <256 MiB; escalation stops for overall p95 >3 seconds, route p95 >3 seconds with at least 20 samples, or errors >1%. Parent SQL reconciliation runs between stages. No check is disabled or relaxed.

## Final 50-user measurements

| Run | Requests | Seconds | Requests/s | Overall p95 ms | Login p50 ms | Login p95 ms | Login p99 ms | Errors | Gates |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| baseline-1 | 1547 | 38.30 | 40.39 | 1979.75 | 1167.44 | 1826.14 | 2045.16 | 1 (0.06%) | FAIL route p95 |
| after-1 | 1953 | 40.80 | 47.87 | 1494.23 | 740.23 | 2717.56 | 2840.17 | 0 (0.00%) | PASS |
| baseline-2 | 1718 | 38.23 | 44.94 | 1869.75 | 1071.60 | 2123.12 | 2232.52 | 1 (0.06%) | FAIL route p95 |
| after-2 | 1948 | 39.54 | 49.27 | 1521.77 | 1034.64 | 2418.39 | 2562.89 | 0 (0.00%) | PASS |

Baseline-1 failed cart-add p95 3755.44 ms (44 samples) and checkout-place 3764.98 ms (21); one cart-add returned 503. Baseline-2 failed cart-read 3155.08 ms (25), cart-clear 3144.93 ms (25), and merchant-stock 3150.99 ms (21); one search returned 503. Neither baseline is a gate pass despite combined p95 below three seconds. Both after runs passed every eligible route and had no transport, HTTP or integrity errors. Each has only 50 login samples; p99 is a single extreme observation, not robust tail certification.

Overall throughput/latency improved in both pairs, but **login p95 was higher after the change than in its paired baseline**. Login is under the gate in both repeats; this is not a universal authentication-tail reduction. The patch reduces protected-request database work, while BCrypt and the burst of logins still compete for CPU and pool service. Two short passes are evidence for this workload, not assurance against longer-run variability.

## Connection contention, CPU and queueing

| 50-user run | Pool acquisition mean ms | Pool hold mean ms | Account-check hold mean / p95 ms | Peak pending | Process CPU peak | Row-lock waits / ms added | Minimum free host MiB |
| --- | ---: | ---: | --- | ---: | ---: | --- | ---: |
| baseline-1 | 588.67 | 192.15 | 158.47 / 328.99 | 40 | 0.62 | 40 / 8729 | 964.66 |
| after-1 | 445.44 | 159.53 | 22.42 / 51.03 | 37 | 0.59 | 56 / 12639 | 298.82 |
| baseline-2 | 515.62 | 171.65 | 150.45 / 305.52 | 37 | 0.49 | 43 / 4452 | 500.66 |
| after-2 | 447.08 | 156.70 | 22.98 / 56.50 | 35 | 0.48 | 63 / 10240 | 413.62 |

Every 50-user stage reached ten active connections. Pool acquisition means remain roughly 445–447 ms after the change: contention is reduced, not eliminated. Deterministic statement-count tests establish removal of one authorization round trip; traces show account-check hold means falling from 150–158 to 22–23 ms. These distributions have different request counts and shared-host effects, so the observed ratio is not a controlled causal effect size.

Public search still holds its connection while page/count/materialization execute. The existing PERF-001 search change is preserved. Checkout, order, cart and merchant transactions still perform fresh validation, customer/inventory locking and atomic writes. Lock-wait counters increased with completed write work; they are stage deltas, not comparable rates without accounting for duration and orders. No lock, durability or isolation level is weakened. No speculative pool-size, thread-count or database tuning is applied.

Native executor observations at 50 users show 50 threads, peak busy 49/50 in the baselines and 48/48 after; sampled queue peak 1/0 before and 0/0 after. This does not include kernel accept/poller/network delay and can miss short peaks. A low sampled executor queue does not rule out CPU scheduling or pool wait.

Login stage p95 wall times are below (nested stages overlap; **do not sum them**). All stages' p50/p95/p99, invocation counts and wall/CPU timings are retained in each auth-summary-50.json and compressed request trace.

| Stage p95 ms | Baseline-1 | After-1 | Baseline-2 | After-2 |
| --- | ---: | ---: | ---: | ---: |
| Observed dispatch queue | 113.30 | 94.02 | 121.71 | 129.71 |
| User-details lookup including acquire | 627.10 | 1072.46 | 1101.91 | 911.02 |
| Connection acquisition, summed per request | 1495.65 | 2357.83 | 1746.44 | 2038.87 |
| BCrypt verification | 276.83 | 319.45 | 278.02 | 296.24 |
| Post-password account lookup | 893.61 | 1511.93 | 853.63 | 1468.54 |
| JWT encoding | 1.46 | 2.82 | 1.16 | 1.77 |
| JSON writing/output | 1.37 | 0.94 | 1.11 | 0.55 |

BCrypt retains strength 10 and holds **zero database connections at entry**. Its CPU/wall difference shows scheduling/wait effects during the login burst; it is not evidence to weaken password work. JWT generation and converter writing are small compared with lookup/pool wait. JSON writing includes output/backpressure. No extra serialization pass is introduced. Authorization/grant lookup remains per bearer request and is separate from login's post-password account read.

Pool means subtract cumulative count/time snapshots (boundary probes included). CPU gauges are process fractions, not host saturation measurements. Raw MySQL samples retain Docker CPU/memory, wait edges and transaction age; reporting windows can exceed nominal CPU quota. Backend RSS is unavailable; JVM used memory includes non-heap and is not RSS. Thread CPU granularity on this host is about 15.625 ms. Probe overhead and enabled slow-query diagnostics affect results. Escaped filter failures may still have default status at filter exit; client status counts are authoritative.

## Environmental stop and higher stages

The attempt capped at 100 used the identical clean source, workload, probes and resource limits. At its **10-user** stage, it completed 286 requests in 15.66 seconds, 18.26 requests/s, overall p95 804.20 ms, login p95 424.27 ms and zero errors. It aborted when sampled free memory reached **255,369,216 bytes (243.54 MiB)**, below 268,435,456 bytes (256 MiB). Its environment had about 986 MiB free at launch, showing why a startup snapshot alone cannot ensure headroom. Stage duration was truncated by the resource gate.

Therefore 50 and 100 users were **not executed in this attempt**. The aborted attempt is retained under controlled/after-100; its name denotes the requested cap, not achieved concurrency. It is not counted as a passing load run. No retry with a relaxed floor, forceful host cleanup, smaller BCrypt cost or higher pool limit was used. A quieter/larger dedicated host and longer repeated tests are required before making a 100-user claim. The earlier unoptimized 100-user baseline also failed (p95 3259.55 ms, errors 1.016%); it is historical evidence, not a current after result.

Free host memory and stage timings varied despite removing competing containers. The original failed run also had closed-connection validation warnings; its missing container-state capture prevents proving whether they stemmed from a database event or host pressure. New runs capture container state (all report OOMKilled=false) and preserve resource evidence, but do not prove absence of host paging. Environmental contribution is plausible, not fully isolated.

## Security, correctness and review evidence

No change to BCrypt strength, JWT signature/issuer/audience/expiry validation, account/grant revocation, tenant membership/active-brand validation, inventory locking, transaction durability or checkout idempotency. Existing regression coverage remains; new full-HTTP failure tests verify fail-closed behavior and sanitized responses. Full local Maven verify: **202 passed, zero failures/errors, one opt-in load test skipped**. The original 200 passing tests are preserved. Hosted [repair CI](https://github.com/Brandista-App/brandista-mvp/actions/runs/40477883963) passed full verify and generator syntax. The disposable harness is run separately; its JUnit success is not substituted for a passing load gate.

All five new runs passed commercial acceptance, stock/quantity reconciliation, unique customer/idempotency keys, fulfillment-brand correspondence and COD/unpaid financial totals at quiescent stage boundaries and completion. After-1 ended with 61 orders / 122 lines / 122 fulfillments; after-2 with 60 / 120 / 120, including each commercial fixture order. Six-table dump/new-schema restore passed in 6.007 and 6.355 seconds respectively. The resource-aborted run also reconciled its nine committed orders and restored successfully. All disposable databases/samplers were removed; the original three development containers were restarted.

[Controlled evidence](evidence/perf-002/controlled/) contains the complete five-run environments, results, SQL counters/plans, timing traces, thread samples, integrity, container state and restore results, plus [summary.json](evidence/perf-002/controlled/summary.json). Original before/after evidence is retained without overwriting failed results. Synthetic dumps, credentials and raw server logs are excluded from Git; timing traces record no tokens, identities, binds or request bodies.

**Merge recommendation:** yes for the bounded authorization-query and fail-closed availability fixes after final-head CI passes. Mark PR #31 ready for technical review with these limits explicit. Do not treat merge as a production launch or commercial-capacity approval. Further performance work must target the still-observed pool/search/write service time on an adequately resourced host; 100 users remains unverified.
