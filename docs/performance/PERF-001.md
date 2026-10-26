# PERF-001: database bottleneck and verified local baseline

## Scope and provenance

PR #29 was verified **MERGED** at 2026-10-25 18:48:34 UTC. `codex/perf-001` starts from its merge commit `40c5ef5`; the PROD-001 branch was not modified. Baseline diagnostics commit: `b8f3a68f6b5015dc4a23a3e4c88df08047ff8417`. Search change: `f3b3f61d19da8e891d4d7476db37dfa7a0248568`.

This investigation uses the original PROD-001 generator, byte-for-byte unchanged (Git blob `cedfdf63f82a383b4421c8b1ea12832e04d1cebc`). It remains a closed-loop 70% browse / 20% customer / 10% merchant mix, BCrypt login at stage entry, five-second ramp, 30-second hold, 500-ms think time, five-second cooldown and six-second request timeout. The catalogue has 1,002 products, two brands, one category and 500 load accounts. The common keyword matches the catalogue; this is not a realistic large-catalogue selectivity distribution.

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

Requests/s is total completed HTTP requests divided by stage duration including ramp/drain. Orders/s is the change in committed order rows divided by that same duration, excluding idempotent replays and the pre-load commercial fixture order. Closed-loop results do not establish arrival-rate capacity or simultaneous checkout capacity. CPU/memory/lock samples are observational, approximately every 3–5 seconds, and may miss brief peaks.

## Interrupted attempt

The first post-change run on 2026-10-26 just after midnight Karachi time left all-NUL `results.json` / `search-plans.json`, a partially corrupt resource file and no completed restore report after the interruption. Its console showed progress, but those logs are insufficient to reconstruct accepted measurements. That attempt is **excluded from the accepted comparison**; no missing percentiles, 100-user result or recovery success was inferred. Earlier intact baseline evidence is retained under `before/` and `reference/`. A fresh baseline and post-change run were required before acceptance.
