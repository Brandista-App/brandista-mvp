# PROD-001 controlled load and recovery results

2026-10-25, Asia/Karachi. This report separates automated correctness, measured short local load, isolated proxy tests, recovery drills and unverified production capacity. No production or third-party system is a load target.

## Reproduction and safety boundary

Requirements: Java21/Maven wrapper, Node24, PowerShell7 and local Docker with the approved MySQL8.4.11 image. From the backend repository, with `JAVA_HOME` set, run:

```powershell
./scripts/load/run-isolated.ps1 -HoldSeconds 30
```

The runner creates a randomly named labeled container, a new `brandista_prod001` schema and random ephemeral credentials, binds MySQL to an allocated **127.0.0.1-only** port, caps it at640 MiB/one CPU, and removes that container in `finally`. The opt-in JUnit class rejects any datasource other than the expected disposable loopback schema and requires an initially empty users table. It uses the real Java application with a512-MiB heap, opt-in production profile and random loopback load target port. The Node generator takes no external target argument, validates the parent fixture, rejects redirects and uses6-second request timeouts. Test flags/credentials are restored on cleanup. Existing user containers/databases are not reset or used for fixture/load/restore writes.

The explicitly authorized synthetic fixture invokes controlled `OperatorProvisioning.INITIAL_CREATE`, then uses actual HTTP customer registration/login, two brand applications, approval, recipient-bound captured invitation acceptance, activation, merchant product creation/stock/publication, mixed-brand COD quote/place/replay, per-brand visibility, supported A-brand fulfillment transitions and customer history. Fixture transport is in-memory and test-classpath-only; the interactive operator console, real email and device UI are not exercised.

For load, the disposable database contains500 synthetic accounts, two brands and1002 products, including dedicated merchant-edit products. Direct bulk insertion creates load fixtures after the HTTP commercial journey; it is not a production onboarding shortcut. Shared checkout SKUs start at one million units to avoid intentional stock exhaustion, not to simulate real inventory size. Per-stage SQL reconciliation proves initial stock equals remaining plus committed order-line quantity; idempotency keys are unique, each fulfillment matches committed order-line brands and all parent COD/UNPAID totals equal their line subtotals.

## Workload and thresholds

Planned stages:10,50,100,250,500 closed-loop virtual users, each with a5-second ramp,30-second hold,500-ms iteration think time and five-second between-stage cooldown. Actual duration includes request drain. Each user performs a real BCrypt login at stage entry, then public brand/search/product browsing.20% also use address/cart/mixed-COD/replay/history operations;10% use authorized merchant catalogue/stock/list/detail operations;70% browse. Full supported fulfillment transitions are verified by the preceding commercial scenario, not repeatedly manufactured as shipped orders by load traffic.

Abort during a stage after100 requests if transport/non-2xx error ratio exceeds5%, a replay integrity mismatch occurs, sampled JVM total used memory exceeds600 MiB or host free memory falls below256 MiB. Stop escalation after any completed stage with combined p95>3 s, any route having at least20 samples and p95>3 s, or error rate>1%. Three monitoring failures also abort. The generator waits for parent SQL reconciliation between stages. Each stage records actual peak logged-in active users; a requested VU count is not evidence that all users reached steady state. No expected409/429/5xx is silently removed from error totals. Higher unexecuted levels are **NOT VERIFIED**, not a claimed capacity.

Measure per-route and combined request counts, achieved requests/s, nearest-rank p50/p95/p99, status/error rates and monitoring failures. Sample backend process CPU, JVM memory, Hikari active/idle/pending connections, generator memory and host free memory approximately every3 s. Missing metrics are null, not zero. A separate Docker sampler records MySQL CPU/memory. SQL snapshots include global connection/lock/slow-query counters and normalized statement digests; cumulative counters must not be misrepresented as per-stage deltas. Raw SQL literals, passwords, tokens and fixture emails are not included in committed performance summaries.

This is a short closed-loop mixed workload on a shared Windows/Docker host, **not an arrival-rate, soak, Internet/TLS, production-proxy or production-sized catalogue test**. Login burst cost and client/OS resource limits can dominate; aggregate request rate is not orders/s or simultaneous checkout capacity. A safe result applies only to the actual measured duration/topology/data mix. Repeat sustained tests on the approved deployment with realistic catalogue/order cardinalities, separate load generator, network latency, TLS/proxy controls, cold/warm cache, distributed users and failover.

## Measured results

Accepted run: **2026-10-25**, clean source `0d33529e5909175e801886c873a9c8f3dba5bc53`, Node24.21.0, Java21.0.12.1, MySQL8.4.11. The runner started18:29Z (23:29 Karachi), finished around23:32 and removed its disposable container and sampler. See committed [environment](evidence/load-environment.json), [stage data](evidence/load-results.json), [MySQL resource samples](evidence/mysql-resource-samples.jsonl), [commercial acceptance](evidence/commercial-acceptance.json) and [integrity checks](evidence/load-integrity.json).

| Requested users | Peak active | Seconds incl. ramp/drain | Requests | Requests/s | p50 ms | p95 ms | p99 ms | HTTP errors | Assessment |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 | 10 | 36.16 | 921 | 25.47 | 163.17 | 564.96 | 925.60 | 0% | PASS local escalation gates; not commercial SLO certification |
| 50 | 50 | 41.14 | 1335 | 32.45 | 1150.93 | 2376.81 | 3043.46 | 0.0749%, one search HTTP500 | FAIL route latency gate; stop escalation |
| 100 | — | — | — | — | — | — | — | — | NOT VERIFIED: preceding stage failed |
| 250 | — | — | — | — | — | — | — | — | NOT VERIFIED: not executed |
| 500 | — | — | — | — | — | — | — | — | NOT VERIFIED: environment not demonstrated safe |

At50 users, cart-add p95 was **3280.3 ms** over44 requests and checkout-preview p95 **3021.5 ms** over22; both exceed the3-second per-route threshold despite the lower combined p95. Merchant route p95s were3217.1 ms catalogue,3312.6 ms stock,3371.9 ms order list and3036.0 ms detail, each with only17 samples. Login p95 was2012.0 ms over50 logins. All route/status/percentile data are retained in JSON. At10 users, search p95 was742.2 ms, already above the proposed500-ms commercial read target. Neither stage establishes commercial capacity.

| Peak sampled resource | 10 users | 50 users |
| --- | ---: | ---: |
| Process CPU gauge (fraction) | 0.558 | 0.521 |
| JVM used memory incl. non-heap, MiB | 262.17 | 280.90 |
| Hikari active / configured max | 9 /10 | 10 /10 |
| Hikari pending requests | 0 | 39 |
| Generator RSS, MiB | 86.54 | 87.50 |
| MySQL Docker CPU (100%=one core reporting unit) | 113.77% | 119.80% |
| MySQL memory /640-MiB limit | 82.97% | 83.80% |
| Monitoring failures | 0 | 0 |

Docker CPU can briefly exceed its configured one-CPU quota due to reporting windows/burst accounting; these are observed samples, not sustained allocated cores. Backend RSS was unavailable and remains null: JVM used memory is not RSS. Windows/Docker/shared-host contention and enabled performance-schema/100-ms slow-query TABLE logging affect throughput.

**Saturation:** pool occupancy reached10 with39 waiters, and a connection-acquisition timeout produced the search500. Throughput rose only about27% for five times as many users. MySQL connection high-water mark was10. Cumulative row-lock waits rose0 to40 and lock wait time reached8137 ms. Global slow-query counts rose193 to592; slow-log table rows185 to584. These are distinct counters including fixture/earlier work; only stated differences are stage deltas.

Product-search row queries dominated cumulative DB time:563 executions/114.31 s; matching count queries563/17.73 s. Investigate representative EXPLAIN plans, joins/count cost and lock/pool occupancy before adding indexes or increasing connection limits. Existing publication/price/brand/category/name indexes remain. Contains/lowercase search, unpaginated brand lists, deep offsets and growing order history need further cardinality testing. No speculative index migration is applied.

**Correctness PASS:**43 load orders plus one preceding commercial order yielded44 orders,88 lines and88 brand fulfillments. Stock plus sold quantities reconciled for both shared SKUs; customer/idempotency uniqueness, financial totals and fulfillment-brand correspondence passed at each quiescent boundary and completion. All parent orders remained COD/UNPAID.

**Restore PASS:** [evidence](evidence/restore.json), **8.874 seconds** including consistent dump, new-schema restore and six comparisons:504 users,1002 products,44 orders,88 items,88 fulfillments and4 merchant events. The approximately309-KiB dump was restored in the same disposable container. This does not prove encrypted remote backups, host-loss recovery, production RPO/RTO or production-sized restore throughput.

An exploratory run is preserved outside Git. Its harness incorrectly assumed every order had two brands even after partial request failures; that assertion was not evidence of backend stock corruption. The accepted harness clears/verifies carts, reconciles actual committed brands/totals and waits for parent checks before escalation. Preliminary rates from the failed harness are not accepted capacity data.

**Post-measurement response fix:** the observed controller DB acquisition failure was a generic500. A subsequent change maps resource/transaction acquisition failures reaching MVC advice to a sanitized503 in the existing error envelope. Two focused HTTP tests verify status/schema and absence of driver/credential text. The table retains the measured500: no improved throughput or repeated load result is claimed for this response-only change. Authentication-filter/other-layer failures need separate handling; this is not blanket overload certification.

## Other verification

The isolated Nginx verifier (`python scripts/load/verify-proxy.py`) passed configuration parsing, trusted local self-signed TLS (explicit test CA, no disabled certificate validation), initial successful requests followed by429s for identity bursts,413 for a2-MiB body and404 for public metrics. It uses a stub upstream, so it does not certify a real backend/TLS deployment or traffic policy behind a CDN/NAT. Its ephemeral test key stays in ignored `target/prod001-proxy`; it is not production signing material.

Prometheus3.15.1 `promtool check rules` passed the four prepared rules. Collector deployment, real notification routing, certificate lifecycle and on-call handling remain **NOT VERIFIED**. The production operations test proves metrics-listener separation, app-port probes and bounded order-history SQL queries; these are automated application tests, not physical-device evidence.

The runner also takes a consistent synthetic database dump and restores it to a **new schema in the disposable container**, comparing users/products/orders/items/fulfillments/events counts. Small synthetic restore success would not establish production-scale RPO/RTO, remote encrypted backups or point-in-time recovery. Raw dumps remain ignored and must never be committed or reused as production seed data.
