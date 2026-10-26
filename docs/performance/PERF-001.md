# PERF-001: database bottleneck and verified local baseline

## Scope and provenance

PR #29 was verified **MERGED** at 2026-10-25 18:48:34 UTC. `codex/perf-001` starts from its merge commit `40c5ef5`; the PROD-001 branch was not modified. Baseline diagnostics commit: `b8f3a68f6b5015dc4a23a3e4c88df08047ff8417`. Search change: `f3b3f61d19da8e891d4d7476db37dfa7a0248568`.

This investigation uses the original PROD-001 generator, unchanged in Git (blob `cedfdf63f82a383b4421c8b1ea12832e04d1cebc`). It remains a closed-loop 70% browse / 20% customer / 10% merchant mix, BCrypt login at stage entry, five-second ramp, 30-second hold, 500-ms think time, five-second cooldown and six-second request timeout. The catalogue has 1,002 products, two brands, one category and 500 load accounts. The common keyword matches the catalogue; this is not a realistic large-catalogue selectivity distribution.

MySQL 8.4.11 remains capped at one CPU / 640 MiB with a 128-MiB buffer pool, performance schema and 100-ms slow-query TABLE logging enabled. The JVM heap remains 512 MiB and Hikari maximum remains 10. Each fixture creates its own loopback-only database/container with ephemeral credentials; real customers, external targets and existing databases are excluded. No security or logging controls were disabled, no account/stock caches were introduced, and no service was publicly deployed.

## Root cause and SQL findings

The original specification explicitly joined brand/category for filtering while the repository entity graph independently fetched them. Its page SQL contained **four joins**: two for filtering and two for hydration. The captured MySQL plan scanned all 1,002 product rows, built hash joins for hydration, performed reference lookups for each result and used a temporary/filesort operation before returning 20 rows. The existing `(publication_status, price, id)` index was a possible key but was not selected for that plan.

The initial diagnostic baseline's quiescent `EXPLAIN ANALYZE` page root took 17.4, 32.5 and 25.0 ms; the count took 7.11, 7.60 and 5.26 ms. Under its 50-user stage, page statement cumulative DB time grew by 65.32 seconds for 286 executions (about 228 ms/execution). These loaded statement times include contention and differ from quiescent plan timings. Full plans and synthetic bind values are in [the initial plans](evidence/perf-001/before/search-plans.json).

The count query uses brand/category active-state indexes and product brand lookup, evaluates the contains predicate across the catalogue, and returns an exact count. `LOWER(name/description) LIKE '%term%'` preserves case-insensitive substring matching and literal wildcard escaping. An ordinary name B-tree does not eliminate this contains scan. Full-text or prefix matching would change semantics, so neither is substituted. Exact counts and offset pagination remain; large catalogues, rare terms, deep offsets and wide descriptions require further testing.

The initial diagnostic 50-user stage reached 10 active connections and 39 waiters. Stage deltas showed mean connection acquisition around 885 ms and mean hold time around 277 ms. Its 30 additional row-lock waits totalled 4.722 seconds; the much greater search time and CPU pressure support search/pool queueing as the dominant observed bottleneck, with locking a secondary contributor. This is a measured diagnosis for the synthetic workload, not a claim about every production workload.

## Change and preserved invariants

`ProductService.publicProductSpecification` now navigates the to-one brand/category paths instead of creating explicit joins. Hibernate reuses the entity-graph joins, retaining eager reference data without duplicate filter/fetch joins. The focused regression checks one join per reference, two SQL statements, database pagination, exact totals, stable price ties and reference names after clearing the persistence context. Existing HTTP discovery tests cover name/description search, mixed case, literal `%`, filters, visibility and sorting.

No migration, index, isolation-level, pool-size, API-contract or transactional-write change is needed for this patch. Existing Flyway migrations remain unchanged.

Cart-add still obtains the customer write lock, loads the cart aggregate, verifies published/available product and active brand/category, and flushes the mutation in one transaction. Its separate cart-item lookup duplicates information already present in the aggregate; it is a possible future round-trip reduction, but the measured dominant search cost does not justify expanding this patch.

Checkout-preview still validates the enabled customer and owned address, reads the cart/items, then locks current product/brand/category rows in product-ID order and checks stock. Placement still revalidates the quote, snapshots COD values, consumes stock, writes the order and brand fulfillments, and clears the cart atomically. Preview/placement locks and per-line inventory reads were retained. Current-account status/grant queries and merchant account/membership/active-brand checks remain live database checks on every protected operation; revocation behavior was not replaced with a cache.

## Measurement method and interpretation

The test-only additions capture parameterized catalogue search SQL, three quiescent `EXPLAIN ANALYZE` repetitions after load has drained, pre-load and per-stage SQL counter snapshots, cumulative Hikari usage/acquisition timers, committed order counts, and sampled MySQL lock-wait edges / transaction age. The observer uses an additional database connection; MySQL global connection high-water marks therefore include it. Timer means use differences of cumulative totals/counts, not a difference of means. Timer window maxima can retain fixture setup activity and are not reported as stage-only hold-time maxima. Boundary probes are included in timer deltas.

Per-stage DB counters are computed against the preceding snapshot; the first stage uses `database-before.json`. The raw top-statement list is cumulative and limited to 25 digests; long digest text may be truncated by MySQL. It must not be summed as if it were a complete profile. Complete captured search SQL is supplied separately. MySQL CPU is Docker's sampled percentage (100% represents one reporting core); brief values above 100% can occur with quota/reporting windows. JVM used memory includes non-heap and is not backend RSS. Missing metrics stay null.

The original gates are unchanged: abort after 100 requests if errors exceed 5%, on replay/integrity failure, JVM used memory above 600 MiB, host free memory below 256 MiB, or three monitoring failures. Stop escalation if combined p95 exceeds three seconds, a route with at least 20 samples exceeds three seconds p95, or errors exceed 1%. Each stage waits for SQL reconciliation before the next. Small route sample counts are exposed rather than treated as strong tail-latency evidence.

Requests/s is total completed HTTP requests divided by stage duration including ramp/drain. Orders/s is the change in committed order rows divided by that same duration, excluding idempotent replays and the pre-load commercial fixture order. Closed-loop results do not establish arrival-rate capacity or simultaneous checkout capacity. API metrics use a nominal three-second interval; the Docker observer sleeps three seconds after its stats/SQL commands, so its actual interval is longer and host-dependent. These observations can miss brief peaks.

## Interrupted attempt

The first post-change run on 2026-10-26 just after midnight Karachi time left all-NUL `results.json` / `search-plans.json`, a partially corrupt resource file and no completed restore report after the interruption. Its console showed progress, but those logs are insufficient to reconstruct accepted measurements. That attempt is **excluded from the accepted comparison**; no missing percentiles, 100-user result or recovery success was inferred. Earlier intact baseline evidence is retained under `before/` and `reference/`. A fresh baseline and post-change run were required before acceptance.

## Accepted paired benchmarks

Fresh runs completed on 2026-10-26 (Karachi), sequentially after full verification ended. Before source: `b8f3a68f6b5015dc4a23a3e4c88df08047ff8417`; after source: `94bb2f4fee126f5b0b2d108cca2b641242762dd5`. Both recorded clean trees. The latter differs from the tested production change only by diagnostics/report documentation. [Machine-readable summary](evidence/perf-001/summary.json) is generated offline with:

```powershell
node scripts/load/summarize-perf.mjs docs/performance/evidence/perf-001 before-repeat after
```

| Run | Requested / peak active | Requests | Requests/s | p50 ms | p95 ms | p99 ms | Errors | Committed orders / orders/s | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| before-repeat | 10 / 10 | 653 | 18.01 | 248.87 | 959.66 | 2486.55 | 0 (0.000%) | 14 / 0.386 | PASS |
| before-repeat | 50 / 49 | 895 | 23.11 | 1602.08 | 3430.47 | 4678.60 | 23 (2.570%) | 12 / 0.310 | FAIL |
| after | 10 / 10 | 1102 | 30.00 | 121.29 | 388.84 | 635.42 | 0 (0.000%) | 24 / 0.653 | PASS |
| after | 50 / 50 | 1662 | 43.24 | 727.00 | 1933.65 | 2787.07 | 1 (0.060%) | 23 / 0.598 | FAIL |

Before 50 users failed combined latency, error rate and brand/search/cart-add route gates; only 49 users reached active login state. After 50 users reached 50 active users with much lower combined latency and one HTTP error, but authentication p95 **3163.25 ms** over 50 requests failed the unchanged route gate. No accepted repeat advanced to 100 users.

| Run / users | Search p95 ms (n) | Cart-add p95 ms (n) | Preview p95 ms (n) | Login p95 ms (n) |
| --- | --- | --- | --- | --- |
| before-repeat / 10 | 1082.24 (161) | 1040.29 (28) | 2011.30 (14) | 2335.79 (10) |
| before-repeat / 50 | 3287.01 (227) | 3854.98 (26) | 5840.51 (13) | 2881.33 (50) |
| after / 10 | 367.86 (270) | 485.93 (48) | 599.75 (24) | 601.56 (10) |
| after / 50 | 1647.17 (442) | 2082.62 (46) | 2455.47 (23) | 3163.25 (50) |

Preview had only 13 samples in the before-50 stage; its tail estimate is weak and below the route-gate minimum of 20. After-50 preview had 23 samples. All raw route percentiles/status counts, including errors, are retained.

## Resources and connection scope

| Run / users | Pool active / max; pending | Mean hold / acquire ms | DB CPU peak % | DB memory peak % of 640 MiB | JVM used MiB | Backend CPU fraction |
| --- | --- | --- | --- | --- | --- | --- |
| before-repeat / 10 | 9 / 10; 1 | 257.30 / 8.11 | 152.22 | 84.82 | 262.16 | 0.371 |
| before-repeat / 50 | 10 / 10; 39 | 332.11 / 1029.80 | 120.24 | 86.53 | 282.68 | 0.449 |
| after / 10 | 9 / 10; 0 | 109.86 / 0.67 | 65.01 | 72.94 | 267.99 | 0.532 |
| after / 50 | 10 / 10; 39 | 179.81 / 543.33 | 90.01 | 73.60 | 281.90 | 0.525 |

| Run / users | Row-lock waits / ms (delta) | Slow queries (delta) | Buffer-pool physical reads (delta) | Peak sampled lock-wait edges | Oldest sampled transaction seconds |
| --- | --- | --- | --- | --- | --- |
| before-repeat / 10 | 6 / 540 | 166 | 1 | 0 | 3 |
| before-repeat / 50 | 17 / 6973 | 255 | 0 | 2 | 2 |
| after / 10 | 15 / 1152 | 16 | 1 | 0 | 0 |
| after / 50 | 40 / 5686 | 66 | 0 | 3 | 1 |

Each stage has six Docker resource samples and zero observer/required-metric failures. Backend RSS is unavailable throughout. Generator RSS peaks were before-repeat/10: 79.82 MiB; before-repeat/50: 94.02 MiB; after/10: 92.95 MiB; after/50: 115.36 MiB. The buffer pool had thousands of free pages and zero physical reads during both 50-user stages; no evidence supports increasing its size for this catalogue.

At 50 users, mean hold time fell from 332.11 to 179.81 ms and mean acquisition time from 1029.80 to 543.33 ms, but the sampled pool still reached 10 active / 39 pending. Row-lock wait count rose with higher completed checkout throughput; total wait time fell from 6973 to 5686 ms. Locks remain a risk, not a problem declared eliminated.

Login has no service-level transaction enclosing BCrypt. The user-details lookup completes before password matching; a separate lookup constructs the token response. Current-account/grant checks on bearer requests and merchant authorization queries remain. The remaining burst-login tail cannot safely be fixed by weakening BCrypt, caching revocation state or raising the pool without evidence. No such change was made. Detailed password-CPU/queue attribution remains future investigation.

## Paired execution plans

| Run | Query | Root actual-time ranges, ms (3 repeats) | Chosen access / operations |
| --- | --- | --- | --- |
| before-repeat | count | 6.89..6.89; 5.86..5.86; 7.04..7.04 | c1_0: idx_categories_status_name (Using index); b1_0: idx_brands_status_name (Using index); p1_0: idx_products_brand_id (Using where) |
| before-repeat | page | 24..24; 25.9..25.9; 25.5..25.5 | p1_0: scan (Using where; Using temporary; Using filesort); c2_0: scan (Using where; Using join buffer (hash join)); c1_0: PRIMARY (Using where); b2_0: scan (Using where; Using join buffer (hash join)); b1_0: PRIMARY (Using where) |
| after | count | 4.09..4.09; 7.08..7.08; 4.87..4.87 | c1_0: idx_categories_status_name (Using index); b1_0: idx_brands_status_name (Using index); p1_0: idx_products_brand_id (Using where) |
| after | page | 3.89..4.05; 6.16..6.23; 5.64..5.85 | p1_0: scan (Using where; Using filesort); c1_0: PRIMARY (Using where); b1_0: PRIMARY (Using where) |

After remediation, the page has two joins, no duplicate hydration hash joins and no temporary-table operation in the representative plan. It **still scans 1002 products and filesorts**; the price index is still not chosen. Quiescent root completion improved from 24.0–25.9 ms to 4.05–6.23 ms. The count plan is unchanged in structure and still scans matching catalogue rows; its timing variation is not presented as a count optimization. No index was added or forced.

The 50-user loaded page statement delta was 70.9785 seconds / 219 executions before (324.10 ms/execution) and 5.6750 seconds / 442 after (12.84 ms/execution). Rows examined fell from about 3029 to 1057 per page execution. The count delta was 44.79 ms/execution before versus 16.80 after, but its plan/code did not change; that difference reflects a less congested system and timing variability. Counts still examine about 1005 rows per execution. These are server statement elapsed-time deltas, not CPU-time measurements or HTTP latency.

## Integrity, recovery and regression results

Both fresh harness invocations completed with `BUILD SUCCESS`, passed SQL reconciliation after each stage and at completion, and removed their own disposable container/sampler. The commercial acceptance files verify recipient-bound invitation activation, mixed-brand COD preview/place/replay, cross-brand denial, per-brand order visibility and independent fulfillment transitions.

Stock plus committed line quantity reconciled for both shared SKUs. Customer/idempotency keys remained unique. Order totals matched line subtotals, all orders retained COD/UNPAID/PKR snapshots, and fulfillment rows matched the actual committed order-line brands. The high synthetic stock level avoids deliberate exhaustion; this load test is not a new last-unit correctness proof. Existing concurrent last-unit, same-key and transaction-rollback regressions also passed in the full suite.

Before: 26 load orders plus one commercial order, 54 lines and 54 fulfillments. After: 47 load orders plus one commercial order, 96 lines and 96 fulfillments. Consistent synthetic dumps restored into new schemas with six table counts matching: **8.052 seconds before / 6.978 seconds after**. These same-container, small-data restores do not establish production RPO/RTO. Raw dumps and ephemeral credentials are excluded from committed evidence.

Full backend `verify`: **197 discovered, 196 passed, one opt-in load test skipped, zero failures/errors**. The opt-in MySQL harness was separately executed for both accepted runs. The six focused discovery/SQL-shape tests also passed. See [verification record](evidence/perf-001/verification.json), [before integrity](evidence/perf-001/before-repeat/integrity.json), [after integrity](evidence/perf-001/after/integrity.json) and the restore/acceptance files alongside them.

## Capacity assessment and remaining risks

| Requested users | Accepted post-change assessment |
| --- | --- |
| 10 | PASS the original short local escalation gates; all ten became active |
| 50 | FAIL authentication p95 (3163.25 ms); one HTTP error and pool saturation remain despite faster catalogue/cart/preview |
| 100 | NOT VERIFIED; fresh accepted repeats stopped at 50, and no result is reconstructed from the corrupt earlier attempt |
| 250 / 500 | NOT VERIFIED; not executed |

The measured catalogue bottleneck is substantially reduced. A stable 50-user scaling baseline is **not established**: the unchanged authentication route gate still fails. The verified passing local stage remains ten closed-loop users for the measured 30-second hold. This is not a production-capacity or commercial-launch certification.

Remaining work is burst-authentication CPU/queue profiling with unchanged BCrypt strength, pool/HTTP-thread queue attribution, realistic catalogue cardinality/selectivity/description sizes, deep pagination and exact-count costs, and sustained tests on an approved isolated deployment with a separate generator and TLS/proxy. The page still scans/filesorts and the exact count still scans matches. Shared product/brand/category locks and per-customer serialization remain deliberate correctness controls; any future narrowing needs dedicated MySQL concurrency evidence. The redundant cart-item lookup and merchant repeated authorization/ownership reads are possible scoped investigations, not permission to remove checks.

Backend RSS and brief resource peaks are unverified; the shared Windows/Docker host and sparse samples limit confidence in numerical speedup ratios. The earlier intact reference runs show material host variability and are retained rather than discarded. Database buffer-pool enlargement, speculative indexes, broader pool limits, caching and password-cost reductions were not justified by these measurements. Dependency/security and operational launch gates from PROD-001 remain unchanged. Authentication-filter pool failures can still produce a server error before MVC advice; this patch does not certify overload handling at that layer.

## Reproduction and evidence layout

Use Java 21, Node 24, PowerShell 7 and local Docker. Set `JAVA_HOME`, then run `./scripts/load/run-isolated.ps1 -HoldSeconds 30` from a clean checkout. Use baseline commit `b8f3a68` for the diagnostic before run and the PR branch for after. Run sequentially, without the test suite competing for resources. Never supply an external load target or point the fixture at an existing database. The runner/harness enforce disposable loopback targets and an initially empty schema.

`reference/` is the unchanged merged baseline reproduction; `before/` is the initial diagnostic reproduction; `before-repeat/` and `after/` are the accepted fresh paired comparison. Each retains environment/source, raw route distributions, resource samples, integrity and commercial acceptance; diagnostic runs also retain pre-load counters and SQL plans. `summary.json` computes stage deltas and rates from the paired files. No passwords, bearer tokens, invitation tokens, real account data or raw database dumps are included.

No PROD-001 branch was overwritten, no pool/security/stock controls were weakened, and no merge, public deployment, paid infrastructure or external load test was performed.
