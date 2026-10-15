# Integration verification — 15 October 2026

Repositories: backend `brandista-mvp` (Java 21, Spring Boot 3.5.16, Maven, JPA, Flyway, MySQL); storefront `../brandista-mobile` (React 19, TypeScript 6, Vite 8, React Router). Docs/admin repositories are empty. Backend working tree was clean before this sprint. No push, merge, public deployment, DNS or hosting purchase was performed.

## Repairs

1. Backend `GET /api/v1/brands` returns `BrandResponse[]`, while frontend typed it as `Page<Brand>` and dereferenced `.content`. Adapter now validates the array and constructs the UI page model. Corrected fixtures and three regression tests cover populated/empty arrays and invalid contracts. No backend API change.
2. Partner form accessed `cause.message` on `unknown`; narrowed it so production TypeScript compilation succeeds.
3. `/brands/:slug` was an informational placeholder. It now resolves an active brand and loads `/products?brandId=...&page=...&size=12`, with cancellation, loading/error/retry, empty state and paging. Two regression tests cover storefront filtering and missing/inactive brands.

All live paths still use real endpoints. No mock fallback or payment/order success was introduced. Sample data belongs only to a guarded, separate file-H2 demo.

## Commands and results

| Check | Result | Evidence and limit |
| --- | --- | --- |
| `docker compose up -d --build` | BLOCKED | Docker engine pipe absent; escalation confirmed unavailable outside sandbox too |
| Initial `mvnw.cmd test` | FAIL to start | JAVA_HOME absent; repaired process environment using installed Java 21 |
| `mvnw.cmd -o test` after JAVA_HOME/MAVEN_OPTS | PASS | 72 tests, no failures/errors/skips; H2, not MySQL |
| Original frontend `npm ci --ignore-scripts --no-audit --no-fund` | FAIL | Locked rolldown native binding EPERM; existing processes preserved |
| Isolated frontend `npm ci --ignore-scripts --offline --no-audit --no-fund` | PASS | 235 packages from local cache; under backend target/launch-frontend |
| Frontend tests/lint/build | See final sprint report | Final commands rerun after storefront changes; earlier build failures repaired |
| `mvnw.cmd -o package -DskipTests` | PASS | Repackaged jar after 72-test run; this package command skipped tests explicitly |
| `docker compose -f compose.yaml -f compose.demo.yaml config --quiet` | PASS | Syntax only; images not built/run |
| `scripts/start-local-demo.ps1` | PASS locally | Real Spring app, main application.yml, test-only launcher/H2 driver, 127.0.0.1:8081 |
| `scripts/test-local-demo.ps1` | PASS | Actual HTTP admin approval/activation/product publication, customer 403/admin boundary, anonymous cart 401, server subtotal 2469.00, persisted cart |

Raw run evidence is in ignored `target/launch-*.log` and `target/launch-api-smoke.json`; credentials/token bodies are not committed. Browser evidence is in BROWSER-QA-REPORT.md and screenshots/. The smoke script's first run failed because its PowerShell wrapper did not enumerate the brand array; correcting the script made the same application workflow pass.

## Startup and data boundaries

Documented MySQL path remains `docker compose up --build`, backend `http://localhost:8080`, MySQL host loopback port 3307. It did not boot in this sprint. Port 8080 and storefront 5173 belonged to pre-existing processes; they were not stopped.

Supplementary verified runtime: `http://127.0.0.1:5175/` -> `http://127.0.0.1:8081/api/v1`, readiness `/actuator/health/readiness`. Vite uses `.env.local` in an isolated source copy with exactly that API root; backend permits exact 5173/5175 origins. Test-run explicitly loads main application.yml so validation and production CORS configuration apply. Database is `target/launch-demo.mv.db`, a disposable file-H2 database, never the MySQL volume. JWT signing key is random per run; restart invalidates earlier tokens. Test launcher and H2 dependency are absent from the packaged production jar.

Remaining gaps: MySQL dialect/runtime/concurrency; formal SRS; stock counts/reservations; checkout/order/payment; profile and address edit UI; admin console; public rate limits/revocation policy; approved product assets; staging and public smoke tests. No genuine product photography or signed partner is asserted.
