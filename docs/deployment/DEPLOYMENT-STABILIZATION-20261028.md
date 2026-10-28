# Brandista deployment stabilization — 28 October 2026

## Decision and scope

Not ready for an end-to-end production launch. Do not merge or deploy automatically. The immediate backend timeout cause is unproven; changing its port, probe path, TLS mode or instance tier is not justified by the available evidence. The shortest safe path is to collect one complete failed-start evidence set, resolve that failure, then complete invitation delivery and the static-site/DNS configuration below.

Audited remote main snapshots: backend `bc6775453ec83f12da238db27e1ddfe43d60d1f8`, admin `a175bee`, frontend `3c6432e`. Clean worktrees preserve unrelated existing local edits, including unfinished backend authentication/email work. That work is not on the audited main and must not be treated as deployed functionality.

## Root causes, confirmed gaps and remaining blockers

| Finding | Evidence and impact |
| --- | --- |
| Previous missing Aiven trust setup repaired in code | Backend PR #32 is merged. Entrypoint preserves public roots, imports mounted CA at runtime, rejects invalid certificates, and runs non-root. Its actual execution and real Aiven TLS chain still need live verification. |
| Current backend unavailable from this test environment | Bounded GETs to the supplied `https://brandista-mvp.onrender.com`: `/readyz` timed out at 45.016 seconds, `/livez` at 15.014 seconds, `/api/v1/brands` at 15.104 seconds, all HTTP 000 with no response bytes. These are unsuccessful external checks, not proof of a database, JVM, Render or egress root cause. |
| Profile/port/path mismatch not established | Owner confirms `production`, `SERVER_PORT=10000`, `/readyz`, CA path and VERIFY_IDENTITY. Code supports precisely that arrangement. Dashboard overrides and complete startup logs remain unverified. Earlier 8080 detection does not prove a port fault. |
| Production invitation delivery missing | `InvitationDeliveryConfiguration` defaults to a delivery implementation that throws 503. Its only concrete transport is encrypted development files, prohibited in production. Real recipient invitation creation/acceptance and activation cannot complete end to end. A tested durable production delivery adapter and provider configuration are required. Do not enable invitation-dev or expose raw tokens as a workaround. |
| Browser builds permit a broken localhost target | Both main branches default to `http://localhost:8080/api/v1` if the build variable is absent. Reviewed fixes reject missing/insecure/loopback/malformed API roots at build time; local dev remains available. Admin CI receives the explicit public intended API root. |
| DNS not cut over | Local public resolver returned apex A records `198.49.23.145`, `198.49.23.144`, `198.185.159.144`, `198.185.159.145`; admin/api names returned NXDOMAIN. This is a point-in-time resolver result, not a complete authoritative DNS audit. |
| Infrastructure evidence/access missing | No authenticated Render/Aiven/Squarespace inspection, complete startup/restart logs, memory/CPU graphs, actual JVM options, SQL grants/history, CA fingerprint, backup/restore evidence or frontend Render URLs available. A Render integration was found and suggested, but is not yet connected; no Aiven integration was returned by provider discovery. |
| First super-admin not verified | Secure offline tool exists; no real account was created or confirmed. Account identity and controlled operator environment remain prerequisites. |
| Sustained Render capacity unproven; cloud smoke passes | GitHub Actions successfully ran the actual production image at 512 MiB / 0.5 CPU with TLS MySQL. Default JVM startup/readiness and a short API/outage smoke fit the limit without OOM. This is not Render's actual CPU/latency/load or Aiven. |

Production invitation transport needs provider selection, verified sender and acceptance destination, protected credentials, durable retry/idempotency and expiry behavior, secret-safe logging, and synthetic plus real approved-recipient delivery tests. Render Free blocks outbound SMTP ports 25/465/587: use a provider HTTPS API on the current tier rather than building an SMTP-only adapter or assuming an upgrade is necessary. This report does not incorporate the unrelated unfinished local email implementation without review. [Render SMTP limitation](https://render.com/changelog/free-web-services-will-no-longer-allow-outbound-traffic-to-smtp-ports).

The existing recipient UI is `https://admin.brandistaapp.com/accept-invitation`: it requires manual secret input and intentionally does not read tokens from URLs. A delivery adapter must respect that contract or introduce a separately reviewed acceptance flow. Do not invent a storefront acceptance route.

Direct Docker hosting also bypasses the prepared separate nginx proxy's identity/application rate limits. Main has no verified production login throttle/MFA layer. Before unrestricted public launch, supply and test appropriate abuse controls for login, registration and public application submissions; do not assume the deployment template's nginx controls run inside this Dockerfile. This is separate from the readiness timeout and does not justify changing health settings.

## Backend startup, readiness and restart diagnosis

Startup sequence: shell validates CA -> keytool creates runtime truststore -> JVM starts -> Spring builds context -> datasource/Flyway connect and migrate -> Hibernate validates schema -> security/JWT config -> application and management listeners -> application ready. Hikari starting or even adding a connection does not prove migration completion, schema validity, readiness or API availability. An open port is not the final `Started BrandistaApplication`/ready event.

`application-production.yml` enables additional `/livez` and `/readyz` paths on the application listener. The readiness group inherited from application.yml includes `readinessState,db`; DB loss can yield 503. Liveness excludes DB. Default management port 9091 binds only `127.0.0.1`; it is deliberately private. The code rejects using the same fixed application and management port. Changing Render to 9091 would be incorrect. Public metrics remain denied. The production integration test explicitly checks app-port ready/live and private metrics.

Without the exact production profile the additional paths are absent; without correct public binding Render cannot connect. Environment overrides of `SERVER_ADDRESS`, `MANAGEMENT_*`, `SPRING_APPLICATION_JSON`, JVM system properties or Docker Command can change the effective behavior. Inspect variable names and safe values only; do not dump the full environment.

Render requires a successful HTTP response within five seconds. For running instances, consecutive failures can remove routing after 15 seconds and restart after 60 seconds; new deploys have a 15-minute readiness window. Graceful Tomcat/JPA/Hikari shutdown is consistent with an external stop/redeploy and is not evidence of the original fault. An OOM kill can be abrupt; a graceful exit does not by itself exclude earlier GC pressure or a later platform stop. [Render health checks](https://render.com/docs/health-checks).

Collect ONE continuous UTC-timestamped log window from the deployment start through the restart, with deploy ID, exact commit, instance ID, elapsed times and platform event/exit reason:

1. Entrypoint `Brandista CA setup: ... imported` or its fail-closed diagnostic.
2. Java options actually applied (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, explicit -Xmx and truststore overrides), active-profile line, Java version, public bind/port and management bind/port. Never publish truststore integrity password, JWT or DB credentials.
3. Hikari `Starting`, added-connection and `Start completed` lines; SQLState/vendor codes and sanitized deepest cause for timeouts, TLS, DNS, authentication or authorization. Remove JDBC credentials/PII before sharing exceptions.
4. Flyway version/schema validation and migration start/success/failure; Hibernate EntityManagerFactory/schema validation; BOTH Tomcat-start lines; final `Started BrandistaApplication` and time to application-ready.
5. Render health failure details: connection refused vs timeout vs returned status; first failed check and restart event. During the same window compare direct app-port `/livez` and `/readyz`, private management readiness (restricted details only if needed), and one public read route. Do not expose actuator env/config/metrics publicly to debug.
6. Memory/CPU graph from before startup through failure; cgroup memory limit/current/peak and `memory.events` OOM counters when available; actual maximum heap, heap/nonheap, thread count, GC pauses, Hikari active/pending and connection limit. Capture exit code/reason (e.g. SIGTERM vs OOMKilled/137) from Render events; 137 alone is not unique proof of OOM.

Render Free has no dashboard/SSH shell or one-off job support. Obtain deploy logs/events and available metrics through the dashboard; do not assume these shell diagnostics can be executed there. Any additional startup diagnostics need a reviewed, secret-safe instrumentation change and a separately approved deployment. Free services sleep after 15 idle minutes and can be suspended for usage limits: inspect service state and billing/usage events alongside the health events. These limitations do not by themselves establish the observed cause. [Render Free behavior](https://render.com/docs/free).

Interpretation: no final startup plus Flyway/JPA/JWT exception = startup failure. `/livez` fast but `/readyz` 503 = readiness dependency/state investigation. Both slow plus GC/CPU saturation = resource investigation. Both locally fast but Render checks time out = binding/platform/network investigation. Health query hanging with no JVM pressure = DB/network/driver timeouts investigation. Record actual responses rather than assuming these branches occurred.

Hikari acquisition timeout is 3000 ms and validation timeout 1000 ms under production. Those settings do not replace a JDBC network socket/query timeout. Inspect sanitized Connector/J `connectTimeout`/`socketTimeout` and a thread dump if the DB check hangs: an idle/broken network connection can exceed Render's five-second budget despite an initialized pool. The full current JDBC URL was not available, so no timeout override is proposed as a confirmed fix.

## Memory audit and controlled validation

Docker uses Temurin Java 21 directly, with no heap flags in Dockerfile or entrypoint. `JAVA_OPTS` alone is not read by this entrypoint; standard `JAVA_TOOL_OPTIONS` is read by Java and keytool. Java 21 is container aware. Default MaxRAMPercentage is 25%; local Java 21 with simulated `-XX:MaxRAM=512m` reported maximum heap 134217728 bytes (128 MiB). This is an ergonomics check on Windows, not a Linux cgroup test. Verify the actual Render container with `-XshowSettings:system`/VM flags before drawing capacity conclusions. [Java 21 options](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html).

Cloud run 40657728081 additionally executed Java from the real Linux image under the 512-MiB container limit: estimated default maximum heap was 123.75 MiB. Its `linux-jvm-ergonomics.txt` artifact supersedes assumptions based only on the Windows probe. Actual Render overrides remain unknown.

Heap is only part of RSS: metaspace, code cache, thread stacks, direct/TLS buffers, GC/native allocations and a second Tomcat listener also count. The production defaults are 64 app HTTP threads, Hikari max 10/min 2, plus management threads. Historical synthetic load recorded about 262–281 MiB JVM used including nonheap with a 512-MiB heap and unavailable RSS; it cannot establish a 512-MiB container budget.

Do not set -Xmx512m or copy the prior 512–768-MiB heap guidance into this instance. Do not blindly increase heap percentage. Cloud run 40656758548 tested both the unmodified defaults and `JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError`, `DB_POOL_MAX=4`, `DB_POOL_MIN=2`, `HTTP_THREADS_MAX=16` in disposable 512-MiB Linux containers. Defaults became ready in 26.529 seconds with sampled Docker memory 282.8–284.0 MiB; the candidate took 27.204 seconds and sampled 290.9–293.5 MiB. Cgroup peak counters were 301.32 and 312.45 MiB respectively, and neither had an OOM event. Native process RSS and cgroup/Docker accounting are distinct; detailed samples are in the artifact.

This is a short smoke, not sustained capacity validation. The CPU quota was 0.5 CPU, not a measurement of the actual Render allowance, and JDBC fixture timeouts were explicitly bounded at 3000 ms. Both configurations returned ready/live/public reads within five seconds, rejected customer super-admin access, passed exact-origin CORS, and switched readiness to 503 during DB loss while preserving liveness, then recovered. The evidence gives no reason to change the production heap or buy a larger instance merely to fix this timeout. Keep >=2 DB connections because independent audit transactions require them; do not cap metaspace arbitrarily. Sustained production CPU, GC and remote-network behavior still require Render evidence.

Repeat cloud runs illustrate host/CPU variability: corrected-suite run 40657454316 measured 34.837/34.966-second default/bounded startup; run 40657728081 measured 51.465/51.553 seconds, with no OOM in either case. Do not present a single 27-second result as a guaranteed cold-start budget. All runs retain the exact 512-MiB memory cap; they do not establish Render's actual CPU quota or a production SLA.

## Exact Render backend settings

| Setting | Value |
| --- | --- |
| Service type/repository/runtime | Web Service / `Brandista-App/brandista-mvp` / Docker |
| Branch/root/Dockerfile/context | Reviewed `main` / repository root / `./Dockerfile` / `.` |
| Docker Command | Empty; use image entrypoint |
| Public listener | `SERVER_PORT=10000`; retain all-interface bind (no loopback SERVER_ADDRESS override) |
| Render PORT | Default 10000; application explicitly uses SERVER_PORT, not PORT |
| Profile | `SPRING_PROFILES_ACTIVE=production` |
| Health | `/readyz` on app port; do not substitute `/healthz` (separate nginx proxy route) or `/livez` |
| CA | Secret File `ca.pem`; `AIVEN_CA_CERT=/etc/secrets/ca.pem` |
| Management | Leave defaults: 127.0.0.1:9091; no public mapping, no same-port override |
| Datasource | Existing corrected real `SPRING_DATASOURCE_URL`, database brandista, `sslMode=VERIFY_IDENTITY`; `SPRING_DATASOURCE_USERNAME=brandista_app`; password only in Render secret setting |
| JWT | Existing protected `JWT_SECRET_BASE64` (>=32 decoded random bytes), `JWT_ISSUER=brandista`, `JWT_AUDIENCE=brandista-api`, `JWT_ACCESS_TOKEN_TTL=PT15M`; preserve deployed matching issuer/audience if already customized |
| CORS | `CORS_ALLOWED_ORIGINS=https://brandistaapp.com,https://admin.brandistaapp.com` |
| Checkout | `COD_CHECKOUT_ENABLED=false` until operationally approved |
| Provisioning | Leave `BRANDISTA_SECURITY_PROVISIONING_ENABLED` and recovery flag absent/false on web process |
| JVM/pool/threads | Inspect actual options first; defaults and conditional constrained candidate above |
| Custom domain | `api.brandistaapp.com`, added and verified on this service |
| Deploy control | Human review and deliberate deploy only; existing On Commit auto-deploy means a merge can deploy |

Keep all secrets out of frontend settings and Git. Exact Aiven hostname/port, password and JWT value are intentionally not reproduced. Do not disable hostname verification. Reject conflicting Connector/J `trustCertificateKeyStoreUrl` or `fallbackToSystemTrustStore=false`/JVM truststore overrides that bypass PR32's merged store. Verify CA fingerprint against Aiven service, certificate hostname, validity, and network allowlist for Render's actual outbound ranges. [Connector/J trust precedence](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html).

## Aiven permissions, migrations and recovery

V1–V14 SQL migrations are present; Flyway runs automatically before JPA (`ddl-auto=validate`). The source uses CREATE TABLE/INDEX, ALTER, foreign keys, and migration INSERT/SELECT backfill, with no need for SUPER, CREATE USER, global grants, routine or trigger privileges. Use a backed-up, approved migration identity with schema-scoped SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES as needed by Flyway/history and these migrations. Do not grant global ALL. Create database brandista through the service owner before app startup. Inspect `SHOW GRANTS`, `SELECT VERSION(),DATABASE(),CURRENT_USER()`, Flyway history through V14/checksums and errors privately; do not edit applied migrations or run repair/clean to bypass failure. [Aiven user privileges](https://aiven.io/docs/products/mysql/howto/manage-service-users).

Preferred separation: run pending migrations once with migration credentials, then web runtime uses table-scoped DML. `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` can supply separate migration credentials if deliberately retained for automatic startup, but offline pre-migration avoids keeping DDL credentials on the web service. Runtime still needs history visibility for Flyway validation. The cloud runtime smoke successfully pre-migrated and then restarted with restricted table grants; the alternative of retaining separate Flyway credentials on the web service was not separately tested.

Runtime: SELECT/INSERT/UPDATE/DELETE only on mutable business tables as required by APIs; `security_provisioning_state` SELECT/UPDATE for invariant locking/state, user/grant/membership rights per supported lifecycle. On `security_audit_events` and insert-only `merchant_order_events`, SELECT/INSERT only; no UPDATE/DELETE/DDL. Give SELECT on flyway_schema_history to runtime after migration, and reserve history writes/DDL for migrator. Broad database-wide UPDATE/DELETE would override audit restrictions: use explicit table grants. No exact production grants were inspected or applied. Keep backup/retention/observer and offline provisioning identities separate. Reserve DB connections for migrations/operator/monitoring and account for all service instances. Confirm Aiven backup restore before migrations and document a compatible application rollback; no destructive down-migration is supplied.

## Exact Render static-site settings

| Setting | Onboarding/storefront | Super-admin/merchant portal |
| --- | --- | --- |
| Repository | Brandista-App/brandista-frontend | Brandista-App/brandista-admin |
| Type/branch/root | Static Site / reviewed main / repository root | Static Site / reviewed main / repository root |
| Node | `NODE_VERSION=24.21.0` (local verified version, compatible with engines) | Same |
| Install control | `SKIP_INSTALL_DEPS=true` | Same |
| Build command | `npm ci && npm run build` | Same |
| Publish directory | `dist` | `dist` |
| Public build env | `VITE_API_BASE_URL=https://api.brandistaapp.com/api/v1` | Same |
| Rewrite | `/*` -> `/index.html`, action Rewrite | Same |
| Custom domain | brandistaapp.com | admin.brandistaapp.com |

VITE variables are compiled into browser assets; rebuild after changing them. No server start command or Docker is needed for static sites. Both use BrowserRouter and need the rewrite for nested paths; publish only dist, never test/visual fixtures/source. CORS is exact-origin allowlisting for /api with Authorization/Content-Type/Idempotency-Key, no wildcard or cookie credential requirement. Temporarily testing on an actual onrender.com frontend origin requires explicitly adding that exact origin to backend CORS. Both clients hold JWTs in memory; reload requires login. Admin confirms current DB-backed SUPER_ADMIN through identity endpoint, with separate merchant capability check; frontend role display is not authority. [Render static sites](https://render.com/docs/static-sites).

## Squarespace DNS — exact types, no invented service targets

First add each custom domain to the matching Render service; then copy its displayed DNS instructions. Preserve MX, SPF, DKIM, DMARC and ownership-verification TXT records.

| Squarespace host | Type | Data |
| --- | --- | --- |
| @ | A | `216.24.57.1` — Render's documented apex load balancer; confirm dashboard instruction at cutover |
| admin | CNAME | Exact hostname assigned to the admin static site; UNKNOWN until created/copied |
| api | CNAME | `brandista-mvp.onrender.com` — supplied confirmed backend hostname; verify dashboard custom-domain instruction |
| www | CNAME | Exact storefront static-site Render hostname; UNKNOWN. Render automatically associates www when apex is added; decide/verify apex canonical redirect. |

Remove the four conflicting Squarespace apex A records only during the approved cutover; remove conflicting A/CNAME/forwarding and AAAA records for these web hosts, not unrelated records. Use provider-supported low TTL initially; wait existing TTL propagation and verify both authoritative and public answers. Do not put https:// or /api/v1 into DNS values. If existing CAA restricts issuance, allow Render's Let's Encrypt and Google Trust Services issuers. Verify each custom domain and managed HTTPS certificate in Render before live use. [Render DNS instructions](https://render.com/docs/configure-other-dns), [domain verification/CAA](https://render.com/docs/custom-domains).

## Secure super-admin bootstrap

No default account/password, startup seed or public bootstrap endpoint exists. Public registration creates CUSTOMER; legacy ADMIN does not imply SUPER_ADMIN. Follow `docs/admin/PR2-SUPER-ADMIN-OPERATIONS.md` using the exact reviewed Java 21 artifact and an interactive trusted terminal. The packaged entry point is `java -Dloader.main=com.brandista.operator.BootstrapMain -cp target/brandista-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher`.

Verify intended Aiven host/database and account identity first; complete migrations and ensure operator connectivity and CA trust. IMPORTANT: invoking BootstrapMain directly bypasses Docker's CA import entrypoint. On the trusted operator machine create a private Java truststore preserving public CAs and adding the verified Aiven CA, configure Java/Connector trust for that invocation, and keep VERIFY_IDENTITY. The existing runbook's direct Java command alone does not install Aiven trust. Do not weaken TLS to bootstrap.

Enter DB credentials using the runbook's protected prompts/environment and cleanup; account password comes from masked Java console prompts, never argv, SQL, pipes, transcripts or a ticket. Enable provisioning only in this one process (`BRANDISTA_SECURITY_PROVISIONING_ENABLED=true`, recovery false). Select INITIAL_CREATE for a verified new dedicated account or INITIAL_GRANT for an independently verified existing ID/email. Initial bootstrap is serialized and permanently single-use; never delete/reset provisioning state. No real identity was supplied or provisioned in this audit. On completion remove one-time flags/credentials/access, inspect audit outcome privately, sign in normally, and verify `/api/v1/super-admin/identity` plus portal access. Recovery/rotation is a separate explicit operation; protect at least one active custodian and follow the runbook for adding a second. A real interactive console/TLS/bootstrap test remains pending.

## Deployment order

1. Collect the complete failed-start evidence above and actual safe dashboard settings in one pass. Review all PRs and current auto-deploy policy before any merge.
2. Verify Aiven CA/hostname/network/grants/schema/history/connection budget and backup/restore. Resolve the demonstrated failure rather than changing several knobs together.
3. Review the passing cloud Java/Docker/CA, real MySQL migration/security suites and 512-MiB TLS runtime/outage smoke. Confirm these against actual Aiven grants and Render settings; run representative sustained load only against an approved isolated environment. Keep the ready probe DB-aware and retain default JVM settings unless live evidence identifies a resource constraint.
4. Implement and verify the missing production invitation delivery transport/provider configuration before promising complete onboarding. Retest token expiry, retry, recipient binding, wrong-account/replay rejection and rollback.
5. After human review, deliberately deploy the backend. Prove continuous /readyz success within Render's budget and no restart/GC/OOM loop before frontend/DNS cutover. Attach/verify api custom domain and DNS with controlled timing.
6. Provision/verify the approved super-admin offline with real CA trust; no web bootstrap flags.
7. Create both static sites with settings above, rebuild against the functioning HTTPS API, test actual Render origins using exact temporary CORS entries. Obtain storefront/admin assigned hostnames.
8. Add/verify custom domains, apply reviewed Squarespace records, confirm certificates/propagation and final CORS. Keep existing site available until cutover is ready.
9. Run the final checklist below with designated synthetic accounts/brand and cleanup ownership. Enable COD only after fulfilment/support operations and an approved test order succeed.

## Final live smoke-test checklist — pending unless explicitly recorded

- Correct deploy SHA, CA import, profile, Flyway V14 and final Started/ready; public /readyz and /livez 200 within five seconds over repeated checks, including cold start/recovery; stable memory/CPU and no restart events.
- Public read API 200, bad JWT 401, ordinary customer denied super-admin 403, public metrics/config remain inaccessible, private management remains private.
- TLS hostname/chain verification to Aiven and HTTPS for all three public domains; exact CORS preflight for both browser origins succeeds and foreign origin is denied; no mixed content/localhost browser requests.
- Direct nested navigation/refresh works on both sites; assets load; login, expiry, logout and reload behavior are understood; no token or password in browser persistence, URLs or logs.
- Submit /partners application with approved synthetic identity; validation and duplicate/error states; super-admin can list/review and approve it.
- Real invitation reaches intended recipient securely; intended new/existing account accepts once; wrong identity, expired/revoked/replayed token denied; membership exists but brand remains inactive until explicit activation; activation gate and merchant isolation work.
- Merchant catalogue/stock changes and public active-brand visibility work; ordinary customer cannot access other-brand management. If COD enabled, approved quote/place/replay/history/fulfilment smoke preserves totals and inventory.
- Record timestamps/statuses and evidence without secrets; confirm backups, rollback owner, support and monitoring. No production writes or bootstrap were performed in this audit.

## Verification results and review links

| Check | Result and limit |
| --- | --- |
| Backend Java 21 Maven verify | PASS: 203 discovered, 202 passed, one opt-in skipped, zero failures/errors; executable JAR built. Suite includes production listeners/readiness/security, migrations on H2, bootstrap service and onboarding with synthetic delivery. |
| Admin | Local checks and cloud CI PASS: typecheck/build, lint, formatting, 155 tests, production dependency audit. Cloud also builds deploy/Dockerfile and checks login/invitation/nested-route SPA fallback and missing-asset 404. This does not test Render's dashboard rewrite. |
| Storefront | Local checks and new cloud CI PASS: TypeScript/Vite build, lint, 69 tests, production dependency audit and missing/loopback production API-root rejection; dist retained as artifact. No live storefront deployed. |
| Build guard regression | Both projects reject missing and HTTP-loopback API configuration; both accept intended HTTPS root. These checks do not contact that API. |
| Docker build | PASS in GitHub Actions, including actual image's Maven build. Local Docker Desktop/WSL ERROR_TIMEOUT is bypassed, not a remaining cloud blocker. |
| CA entrypoint Docker smoke | PASS: all seven synthetic cases, original Java public CAs preserved, mounted CA accepted, fail-closed invalid input and secret-file access. No real Aiven chain checked. |
| Real MySQL migrations/permission smoke | PASS: actual production JAR applies Flyway V1–V14 over VERIFY_IDENTITY TLS to disposable MySQL 8.4.11, then starts with table-scoped runtime grants without DDL/audit UPDATE. Five corrected MySQL suites execute 88 tests: PR2 17, PR3 16, PR4 18, PR6B 17, PR7A 20; zero failures/errors/skips. No Aiven instance contacted. |
| 512-MiB Linux resource smoke | PASS for default and bounded JVM configurations under a 512-MiB / 0.5-CPU limit; startup, public reads, registration/login/application submission, customer privilege denial, exact CORS, DB loss/readiness/recovery. Short smoke only; production load and actual Render CPU are unverified. |
| External backend HTTP | Local 120-second retry timed out. Cloud observer at 08:27:46 UTC (13:27:46 Pakistan time) also timed out after ~120 seconds for /readyz, /livez and /api/v1/brands. No successful live API result. |
| DNS | Resolver reads completed; apex still Squarespace IPs and admin/api NXDOMAIN at observation. No records changed. |
| Live frontend/auth/bootstrap/invitations | NOT RUN; sites/credentials/provider unavailable. No real production writes. |

Local raw check logs remain uncommitted in the clean audit worktrees. The initial Maven invocation lacked JAVA_HOME; rerun with the installed Java 21 path completed successfully. Host contention materially affected timing; no test timeout change was committed. Previous PR #32 CI/Docker evidence is historical, not a new Docker or Aiven test here: [CA repair PR32](https://github.com/Brandista-App/brandista-mvp/pull/32).

Reviewable fixes: [storefront PR4](https://github.com/Brandista-App/brandista-frontend/pull/4), including build guards and new cloud CI; [admin PR6](https://github.com/Brandista-App/brandista-admin/pull/6), including build guards, existing CI and optional nginx Docker/SPA verification; [backend PR33](https://github.com/Brandista-App/brandista-mvp/pull/33), including this report, cloud MySQL matrix, TLS/resource smoke and read-only Render observation. All are draft review PRs on `codex/deployment-stabilization-20261028`. Backend production runtime is deliberately unchanged without evidence of a failure mechanism. No merge or deploy performed.

Cloud evidence: [Docker build/CA and 512-MiB TLS runtime](https://github.com/Brandista-App/brandista-mvp/actions/runs/40656758548), [corrected real MySQL test execution](https://github.com/Brandista-App/brandista-mvp/actions/runs/40657454316), [read-only Render observations](https://github.com/Brandista-App/brandista-mvp/actions/runs/40656936997), [storefront CI](https://github.com/Brandista-App/brandista-frontend/actions/runs/40656671312), [admin verification](https://github.com/Brandista-App/brandista-admin/actions/runs/40655653845). Downloadable artifacts contain synthetic test reports and sanitized runtime evidence only, not provider credentials or private test keys.

The current admin [cloud run 40658004932](https://github.com/Brandista-App/brandista-admin/actions/runs/40658004932) passes both verification and Docker/SPA jobs at `eb02383`. An initial Docker smoke hit a connection reset before nginx was ready; the fixed harness uses a bounded startup wait before assertions. That was a fixture race, not evidence of a deployed admin defect.

The latest executable backend revision `c10a39e` passes all seven jobs in [run 40657728081](https://github.com/Brandista-App/brandista-mvp/actions/runs/40657728081), including the corrected 88 real MySQL tests, full Java suite, Docker/CA and TLS/resource smoke with Linux ergonomics. Later report-only edits do not alter that tested executable behavior. All three PRs remain draft and unmerged.

Evidence correction: the first Linux MySQL matrix at dd6317f/ec3ff42 performed SQL migration-preservation assertions but accidentally splatted a scalar wrapper path into characters and skipped Maven, producing empty logs. Those green jobs are NOT counted as API integration passes. Commit a3c731f forces a string-array argument list and requires nonzero Maven test evidence plus BUILD SUCCESS. Downloaded corrected artifacts confirm all 88 tests actually executed. No production runtime fix was inferred from that CI-runner error.
