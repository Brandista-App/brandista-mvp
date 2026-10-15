# Brandista integration sprint report — 15 October 2026

**Readiness: LOCAL CATALOGUE/AUTH/CART DEMO VERIFIED; FULL TRANSACTIONAL MVP AND DEPLOYMENT BLOCKED.** This records one execution session, not an assertion that 48 hours elapsed or that all milestones are accepted.

## Discovered and repaired

Backend is Java 21/Spring Boot 3.5.16 with MySQL, Flyway V1–V8, JWT, registration, customer addresses/cart, public catalogue, ADMIN brand/category/product management and brand-application review. Frontend is React/TypeScript/Vite web, not a separate native mobile app. Existing F0.6/F0.7/F0.9/F1.0 reports already documented blocked checkout/deployment. No authoritative SRS, separate F0.8 report or populated docs/admin repository was found.

Three application defects/integration gaps were repaired in the existing frontend:

- Real brand-array responses no longer break home, filters and brand listing; invalid contracts produce a clear error instead of an endless loader.
- Partner-intake unknown-error handling now compiles under TypeScript.
- Brand storefront placeholder now displays the active brand and real paginated products using the existing responsive product grid, with loading/error/empty states.

Added five regression cases (three adapter, two storefront); corrected existing unrealistic brand fixtures. Added guarded local H2 demo startup and repeatable real-API smoke scripts. Added a private Compose storefront overlay and nginx same-origin proxy. No order, payment, stock-count rule or successful purchase was invented; PWA was deferred.

## Executed checks

| Check | Status | Result |
| --- | --- | --- |
| Backend `mvnw.cmd -o test` | PASS | 72 tests; 0 failures/errors/skips; H2 only |
| Backend `mvnw.cmd -o package -DskipTests` | PASS | Deployable jar produced after the suite; packaging command explicitly skips tests |
| Production jar inspection | PASS | Test demo launcher and H2 dependency absent |
| Isolated frontend dependency install | PASS | 235 locked packages installed offline without scripts |
| Frontend final build/test/lint | PASS | TypeScript + Vite build, 26 tests in 3 files, ESLint; see target/launch-frontend-*-final3.log |
| Compose demo config parsing | PASS | `docker compose -f compose.yaml -f compose.demo.yaml config --quiet` |
| MySQL/full Compose boot | BLOCKED | Docker engine unavailable even outside sandbox; no MySQL migrations/runtime verified |
| Local real-browser customer journey | PASS within supported scope | Home/category/product, register/login, cart mutations, logout/login persistence; screenshots saved |
| Five responsive sizes | PASS, bounded | Home/cart no horizontal overflow; full all-route/device QA not claimed |
| Brand storefront/browser merchant intake | PASS | Actual backend responses rendered; synthetic application received |
| Real HTTP operator/security smoke | PASS | Approval creates inactive brand, separate activation, create/publish product; anonymous cart 401/customer admin 403; subtotal/persistence assertions |
| File-H2 restart persistence | PASS | Browser-created account and cart survived backend restart |
| Checkout/order/payment | BLOCKED | Missing application features, not just an environment failure |
| Public/deployed smoke | NOT RUN | No approved host/domain/secrets, no deployment performed |

The initial Maven command lacked JAVA_HOME; a process-local Java 21 setting fixed it. Direct frontend npm ci failed on a locked rolldown binding; an isolated copy avoided stopping other processes. Initial frontend production compilation failed on unknown-error handling; a later new-test matcher option also failed typechecking and was corrected before final success. An initial demo attempt hit occupied port 8080; only our own demo processes were restarted on 8081. The test-run launcher explicitly loads main application configuration to avoid test-resource CORS/JPA overrides. These failed attempts are not reported as passes.

## Files modified/created

In sibling `brandista-mobile`: `src/api/brandista.ts`, new `src/api/brandista.test.ts`, `src/app.tsx`, `src/app.test.tsx`, `README.md`. Existing package manifests/lockfile were preserved; the failed npm install affected ignored node_modules only. Final commands ran against matching copied source/config and the original lockfile under backend target/launch-frontend.

In `brandista-mvp`: new `src/test/java/com/brandista/launch/LocalDemoApplication.java`, `scripts/start-local-demo.ps1`, `scripts/test-local-demo.ps1`, `compose.demo.yaml`, `deploy/frontend.Dockerfile`, `deploy/nginx.conf`; all six docs/launch reports and genuine JPEG screenshots. No production Java API/schema changes. Generated jar, file-H2 database, generated local password, isolated frontend dependencies/build and raw logs remain ignored under target. No secrets are added to tracked files.

## URLs and evidence

- Verified local supplementary demo: `http://127.0.0.1:5175/`.
- Verified local backend: `http://127.0.0.1:8081/actuator/health/readiness`; API `/api/v1`.
- Documented normal MySQL backend: localhost:8080, database loopback:3307 — not successfully booted this sprint.
- Prepared Compose storefront: loopback:8090 — configuration only, not running/verified.
- Deployed demo URL: **none**.
- Screenshots/workflow details: [BROWSER-QA-REPORT.md](BROWSER-QA-REPORT.md).
- API smoke/restart JSON and backend/frontend command logs: ignored `target/launch-*` files; not production monitoring.

Local processes started for this demo remain available for review. Existing 8080/5173 processes were preserved. The browser's temporary viewport override is reset at completion. Do not use the generated credentials or demo launcher outside local synthetic testing.

## Remaining blockers and owner actions

1. Supply the authoritative SRS and acceptance decisions. Traceability currently uses provisional audit IDs in [SRS-MVP-COVERAGE.md](SRS-MVP-COVERAGE.md).
2. Restore Docker Desktop's Linux engine and validate MySQL credentials without deleting volumes. Run the normal Compose path, verify readiness, V1–V8 and actual persisted customer/cart behavior on MySQL.
3. Approve whether a catalogue/cart demonstration meets the immediate client objective. A purchasing MVP still needs defined checkout/order/price snapshots, stock semantics, fulfilment, payment/sandbox and policies; these cannot be accepted as complete.
4. Resolve the original frontend's locked native dependency when its owning process can be stopped; reinstall from the unchanged lockfile and rerun checks there. The isolated source copy is already verified.
5. Supply approved brand/product content/photo rights and scope decisions for address editing, merchant tenancy/admin UI. Current samples are explicitly fictional; no real stock counts exist.
6. Provide an approved isolated hosting target, access, domain/TLS plan and explicit deployment/exposure authorization. Apply abuse controls and agree data retention/incident ownership before real submissions. Perform encrypted backup/restore and rollback drills, then run deployed browser smoke tests.

See [DEPLOYMENT-RUNBOOK.md](DEPLOYMENT-RUNBOOK.md) and [CLIENT-DEMO-RUNBOOK.md](CLIENT-DEMO-RUNBOOK.md) for exact commands. No push, merge, purchase, DNS change, public exposure or production deployment was performed. **NEEDS REVIEW**: business scope, security policy and formal SRS conformance. The full release remains **BLOCKED**, despite the useful verified local demonstration.
