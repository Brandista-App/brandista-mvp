# PROD-001 controlled load and recovery results

2026-10-25, Asia/Karachi. This report separates automated correctness, measured short local load, isolated proxy tests, recovery drills and unverified production capacity. No production or third-party system is a load target.

## Reproduction and safety boundary

Requirements: Java21/Maven wrapper, Node24, PowerShell7 and local Docker with the approved MySQL8.4.11 image. From the backend repository, with `JAVA_HOME` set, run:

```powershell
./scripts/load/run-isolated.ps1 -HoldSeconds 30
```

The runner creates a randomly named labeled container, a new `brandista_prod001` schema and random ephemeral credentials, binds MySQL to an allocated **127.0.0.1-only** port, caps it at640 MiB/one CPU, and removes that container in `finally`. The opt-in JUnit class rejects any datasource other than the expected disposable loopback schema and requires an initially empty users table. It uses the real Java application with a512-MiB heap, opt-in production profile and random loopback load target port. The Node generator takes no external target argument, validates the parent fixture, rejects redirects and uses6-second request timeouts. Test flags/credentials are restored on cleanup. Existing user containers/databases are not reset or used for fixture/load/restore writes.

The explicitly authorized synthetic fixture invokes controlled `OperatorProvisioning.INITIAL_CREATE`, then uses actual HTTP customer registration/login, two brand applications, approval, recipient-bound captured invitation acceptance, activation, merchant product creation/stock/publication, mixed-brand COD quote/place/replay, per-brand visibility, supported A-brand fulfillment transitions and customer history. Fixture transport is in-memory and test-classpath-only; the interactive operator console, real email and device UI are not exercised.

For load, the disposable database contains500 synthetic accounts, two brands and1002 products, including dedicated merchant-edit products. Direct bulk insertion creates load fixtures after the HTTP commercial journey; it is not a production onboarding shortcut. Shared checkout SKUs start at one million units to avoid intentional stock exhaustion, not to simulate real inventory size. Per-stage SQL reconciliation proves initial stock equals remaining plus committed order-line quantity; idempotency keys are unique, each mixed order has two fulfillments and all parent orders remain PKR COD/UNPAID at the expected amount.

## Workload and thresholds

Planned stages:10,50,100,250,500 closed-loop virtual users, each with a5-second ramp,30-second hold,500-ms iteration think time and five-second between-stage cooldown. Actual duration includes request drain. Each user performs a real BCrypt login at stage entry, then public brand/search/product browsing.20% also use address/cart/mixed-COD/replay/history operations;10% use authorized merchant catalogue/stock/list/detail operations;70% browse. Full supported fulfillment transitions are verified by the preceding commercial scenario, not repeatedly manufactured as shipped orders by load traffic.

Abort during a stage after100 requests if transport/non-2xx error ratio exceeds5%, a replay integrity mismatch occurs, sampled JVM total used memory exceeds600 MiB or host free memory falls below256 MiB. Stop escalation after any completed stage with p95>3 s or error rate>1%. Each stage records actual peak logged-in active users; a requested VU count is not evidence that all users reached steady state. No expected409/429/5xx is silently removed from error totals. Higher unexecuted levels are **NOT VERIFIED**, not a claimed capacity.

Measure per-route and combined request counts, achieved requests/s, nearest-rank p50/p95/p99, status/error rates and monitoring failures. Sample backend process CPU, JVM memory, Hikari active/idle/pending connections, generator memory and host free memory approximately every3 s. Missing metrics are null, not zero. A separate Docker sampler records MySQL CPU/memory. SQL snapshots include global connection/lock/slow-query counters and normalized statement digests; cumulative counters must not be misrepresented as per-stage deltas. Raw SQL literals, passwords, tokens and fixture emails are not included in committed performance summaries.

This is a short closed-loop mixed workload on a shared Windows/Docker host, **not an arrival-rate, soak, Internet/TLS, production-proxy or production-sized catalogue test**. Login burst cost and client/OS resource limits can dominate; aggregate request rate is not orders/s or simultaneous checkout capacity. A safe result applies only to the actual measured duration/topology/data mix. Repeat sustained tests on the approved deployment with realistic catalogue/order cardinalities, separate load generator, network latency, TLS/proxy controls, cold/warm cache, distributed users and failover.

## Measured results

Execution results, exact counts, resource observations and restore outcome are recorded below only after the disposable runner completes. Until then, all capacity levels are **NOT VERIFIED**. Existing Java regression and standalone proxy verification are not substituted for these measurements.

## Other verification

The isolated Nginx verifier (`python scripts/load/verify-proxy.py`) passed configuration parsing, trusted local self-signed TLS (explicit test CA, no disabled certificate validation), initial successful requests followed by429s for identity bursts,413 for a2-MiB body and404 for public metrics. It uses a stub upstream, so it does not certify a real backend/TLS deployment or traffic policy behind a CDN/NAT. Its ephemeral test key stays in ignored `target/prod001-proxy`; it is not production signing material.

Prometheus3.15.1 `promtool check rules` passed the four prepared rules. Collector deployment, real notification routing, certificate lifecycle and on-call handling remain **NOT VERIFIED**. The production operations test proves metrics-listener separation, app-port probes and bounded order-history SQL queries; these are automated application tests, not physical-device evidence.

The runner also takes a consistent synthetic database dump and restores it to a **new schema in the disposable container**, comparing users/products/orders/items/fulfillments/events counts. Small synthetic restore success would not establish production-scale RPO/RTO, remote encrypted backups or point-in-time recovery. Raw dumps remain ignored and must never be committed or reused as production seed data.
