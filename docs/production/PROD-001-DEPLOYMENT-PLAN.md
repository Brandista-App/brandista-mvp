# PROD-001 deployment and operations plan

2026-10-25. **Preparation only.** No public deployment, paid service, application-store publication, live payment or production account provisioning is authorized by this work.

## Release prerequisites and configuration

Owner approves region, budget, data policy, hostnames, ingress, operator/on-call, TLS lifecycle, backup RPO/RTO and an exact release commit. Review frontend PR3 separately; it was still open at audit. Resolve the commercial security/device/delivery blockers in the audit before launch approval.

Use the existing production-like Compose and HTTPS template as a starting point, not a ready-to-run public environment. Select `SPRING_PROFILES_ACTIVE=production`; inject a random 32-byte-or-longer base64 JWT key, unique issuer/audience, explicit exact CORS origins, and private MySQL URL with `sslMode=VERIFY_IDENTITY`. Validate the server certificate chain/hostname. Mount provider CA/trust material as required by the driver. Templates contain no approved hostnames, certificates or secret values. Fail-fast identity/DB configuration is intentional.

Separate runtime, migration, backup and monitoring DB accounts. Runtime gets only necessary table SELECT/INSERT/UPDATE/DELETE and no GRANT, FILE, SUPER, schema creation or user administration. Migration credentials are held only by the release job. Run Flyway with migration credentials before application rollout, then disable runtime migration with `SPRING_FLYWAY_ENABLED=false` and retain Hibernate schema validation. The default developer setup uses a broader migration/runtime principal and is not evidence of least-privilege production execution. Prove these grants in staging first. Never copy disposable test root credentials to a deployment.

The new profile bounds pool acquisition (3 s), validation (1 s), transaction defaults (10 s), servlet threads/connections/backlog, header size and connector timeouts. These are explicit budgets, not a guarantee that every statement or HTTP request is cancelled at that instant. Configure a driver socket/connection timeout and MySQL lock-wait budget appropriate to the approved TLS URL, then exercise timeout/retry/idempotency behavior. Proxy reads time out at 30 s; clients must preserve the same checkout key for uncertain results. Never silently retry a new checkout key after a timeout.

The prepared Nginx template limits general requests per peer IP (20/s, burst40), identity/public-application submissions (6/min, burst5), simultaneous connections (40), bodies (1 MiB), header/body arrival and upstream waits. Rejections use HTTP429. Initial values require NAT/mobile-network acceptance and operational tuning. They are per-proxy/per-IP controls, not distributed per-account brute-force prevention. If a CDN/load balancer is added, configure only explicitly trusted proxy IP ranges before using forwarded addresses; do not trust arbitrary `X-Forwarded-For`. Block direct backend ingress so rate limits cannot be bypassed. API logging excludes query strings/bodies; apply short retention and restrict IP/path access.

## Reviewed deployment automation sequence

1. Verify protected PR checks, dependency/security reports and signed-off launch gates. Build once from the approved SHA. Produce SBOM, image/bundle digests and provenance; pin approved base/runtime images by immutable digest. No production secrets enter the build. The current image family uses tags; immutable pinning and recurring image scans remain required gates.
2. Independently snapshot/backup the database and record a verified restore point. Confirm free disk, connection headroom and a rollback window. Quiesce checkout if a schema change cannot remain compatible.
3. Run Flyway once with migration credentials. No historical V1–V14 migration is edited by PROD-001. Stop on checksum/schema errors; never use automatic `repair`, `clean` or destructive rollback against business data.
4. Start a new application instance with runtime-only credentials and COD initially disabled. Confirm `/actuator/health/readiness` (not merely an open TCP port), private metrics, exact CORS/TLS, sanitized logs and denial of public metrics/admin escalation.
5. Deploy versioned static customer/admin bundles with the approved API origin. Validate content hashes, CSP/security headers, browser caching and route fallback; production-specific browser/header acceptance is not established by a Vite build alone.
6. Run isolated synthetic acceptance in the approved staging environment, then explicitly approve COD availability and a narrow production smoke procedure. Never seed the production database from the load fixture.
7. Shift traffic gradually and observe errors/latency/locks for an agreed interval. Drain old requests using readiness removal and graceful shutdown. Compose's 40-second stop grace exceeds the app's 30-second drain; the load balancer must remove an instance before terminating it.
8. Record deployment manifest, owner, timestamps, digests and verification without tokens/PII. Retain previous app/web artifacts. No deploy workflow with working production credentials is created or executed in this milestone.

## Rollback

For application-only changes, stop rollout, disable new checkout if integrity is uncertain, remove unhealthy instances from ingress and restore the last reviewed image/static bundles with compatible schema. Preserve logs, request IDs, idempotency keys securely and all order rows; never replay uncertain payments/orders with new keys. PROD-001 adds no migration, so its runtime/config rollback does not require a database restore.

For schema changes, prefer a forward repair or an expand/contract-compatible older app. Database restore is a separately approved incident operation with explicit data-loss acknowledgement and reconciliation of all post-snapshot orders. A VM rollback alone can lose committed orders. Never execute a down migration or overwrite a current database automatically.

## Incident response

| Signal | First action | Escalation/recovery |
| --- | --- | --- |
| Readiness down / DB unavailable | Keep liveness separate; remove instance from traffic, inspect DB/TLS/pool availability | Restore dependencies before restart loops; preserve pending checkout keys |
| 5xx/latency increase | Identify affected route, deploy version, pool pending, CPU/GC and DB locks | Roll back app if correlated; do not add connections/replicas until DB headroom is known |
| Inventory/order mismatch | Disable new COD placement by approved configuration/rollout; preserve rows and evidence | Reconcile order items, stock and idempotency records; no automatic destructive repair |
| Secret or privileged-account compromise | Restrict ingress and involved account/grants, preserve audit trail | Rotate credentials/signing material through owner-approved procedure; account changes invalidate current authority checks |
| Backup/certificate failure | Escalate before expiry/RPO breach | Verify replacement/restore in isolation and record outcome |

Assign actual people, contact channels, response times and incident authority before launch; a document is not an operated on-call service.

## Backup/restore policy and drill

Proposed initial targets for owner approval: maximum 24-hour data loss for an early explicitly agreed pilot, restore within four hours; a commercial order business may require point-in-time recovery and a much smaller RPO. Targets are not current guarantees. Encrypt backups off-host, isolate backup credentials/keys, restrict delete access, define retention and test restore monthly and before destructive changes. Managed backups require independent restore and access checks too.

The disposable runner takes a consistent `mysqldump`, restores to a new isolated schema and compares six commerce/security-related table counts; its measured outcome is recorded in load results. That small synthetic drill does not establish production restore time, encrypted remote retention, binlog recovery or application-level recovery. A future full drill must restore a production-sized sanitized dataset, validate Flyway, log in using synthetic accounts, repeat the commercial workflow, test failure interruption and document achieved RPO/RTO without touching live order data.
