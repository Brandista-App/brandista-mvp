# Address and checkout acceptance — 15 October 2026

**PASS for the implemented local address/COD flow. Owner manual acceptance: PENDING.** The owner approved the SRS update and implementation; agent testing does not establish personal owner acceptance. Public deployment and Android work were not performed.

## Audit and approved scope

The backend already had customer-owned address POST/GET/PUT/PATCH/DELETE and persistent customer carts. The frontend showed addresses read-only; checkout redirected to cart and the checkout button was disabled. Counted inventory, order schema, placement, snapshots, idempotency and history did not exist. Those working address/cart APIs were extended rather than recreated.

The historical full SRS remains unavailable. The owner explicitly approved the proposed local policy and requested an SRS update. [docs/SRS.md](../SRS.md) indexes the [approved addendum](../requirements/ADDRESS-CHECKOUT-SRS.md). Existing B0.x/F0.x/F1.0/M0.1 numbering was preserved; the new database migration is the next unused version, **V9**.

Approved policy: one customer order can contain multiple brands, COD only, PLACED/UNPAID, zero additional delivery/tax/discount in the local MVP, and explicit integer stock. Existing products migrate with stock zero; only the known fictional demo products are initialized automatically. No money collection, cancellation/restocking, fulfilment transition or vendor shipment-splitting API is introduced.

## Implemented

- Existing address APIs now accept delivery instructions; the customer has one default reference. Address mutations serialize on the customer row. First address defaults automatically; deletion selects the newest remaining address or none. The web form supports create/edit/delete/default and checkout selection, with validation and feedback.
- Checkout review loads current eligible product/brand/category state and stock, calculates decimal PKR totals, and binds the delivery/item snapshots into a review token. Placement recalculates; client prices, totals, payment states and owner IDs are not trusted.
- One transaction locks customer and current products, decreases stock, persists order/item/address snapshots, and clears the purchased cart. Failures roll back. A customer-scoped UUID key and database uniqueness constraint prevent duplicate successful orders. Matching retries return the original order, including after cart clearing or source-address deletion.
- Frontend submission guards prevent repeated clicks. Uncertain network/server outcomes retain the exact UUID/address ID/review token in customer-scoped sessionStorage, allowing a safe retry after reload/login; access tokens and delivery PII are not stored there.
- Authenticated customer history/details return only owned orders. Snapshot fields are independent of saved addresses/current product or brand metadata. Historical delivery details survive address edit/deletion.
- ADMIN-only stock updates use the same locking path. Local acceptance startup enables COD explicitly; other environments default to disabled.

## Executed checks

| Check | Result | Actual evidence / limits |
| --- | --- | --- |
| Initial existing backend regression after V9 | PASS | 72 tests, 0 failures/errors/skips, isolated H2 |
| Full backend suite with new order/default tests | PASS | 79 tests, 0 failures/errors/skips; includes two-buyer stock race, same-key race and injected persistence failure |
| Checkout/security after browser CORS repair | PASS | 15 targeted tests (8 checkout/default/CORS and 7 authorization), 0 failures/errors/skips. This is a separate run, not an invented full 80-test run |
| Forced failure after order flush | PASS, H2 fault injection | Cart repository persistence failure after order flush caused order, stock and cart to roll back; no forced post-flush MySQL storage fault was injected |
| Frontend regression suite | PASS | 31 tests in 4 files; includes address CRUD/default, lost-response/reload retry with same key, stock/review rejection, history and safe 404 |
| Frontend production build and lint | PASS | TypeScript + Vite, ESLint; no warnings/errors in final runs |
| Backend packaging | PASS | `mvnw.cmd -o package -DskipTests`; packaging explicitly skipped tests after successful test runs |
| Database migration | PASS | Flyway validated V1–V8, applied V9 on actual MySQL 8.4.11; JPA schema validation succeeded |
| MySQL address CRUD/default/ownership | PASS | Real HTTP create/update/default/delete; other-customer GET/PUT/DELETE/default/preview return 404 |
| MySQL server totals/payment state | PASS | Two brands, 2 × 2499.95 + 1250.50 = 6250.40. Submitted false total/PAID fields were ignored; stored total was 6250.40 and status UNPAID |
| MySQL simultaneous same key | PASS | HTTP 201/200, same order ID, one order row and one decrement |
| MySQL competing customers/last unit | PASS | HTTP 201/409, one sale, stock exactly zero |
| MySQL stale-price/ineligible rejection | PASS | 409; existing cart, stock and order count unchanged |
| MySQL history and role guards | PASS | Other customer's order 404, other history empty, anonymous 401, CUSTOMER stock mutation 403; disabled account cannot read checkout/history |
| Snapshot integrity | PASS | Address edited/deleted and product name/price changed; original order address/line/total retained. Idempotent replay still works after source-address deletion |
| Actual browser journey | PASS | Isolated Microsoft Edge via Playwright, real React/Spring/MySQL. Register/login, two addresses, default/edit, two-brand cart/update, checkout/review, COD, confirmation, empty cart, address deletion, history/details, logout/login |
| Browser errors | PASS, bounded run | No JavaScript exceptions or failed API requests in the successful complete run |
| Responsive checkout | PASS, bounded | 360×800, 390×844, 768×1024, 1366×768, 1920×1080: no horizontal overflow; screenshots captured. Mobile receipt also checked. Full accessibility/device certification is NOT RUN |
| Restart persistence | PASS | Both MySQL container and acceptance Java backend restarted; reauthentication, complete API/browser-created orders, snapshots and direct inventory rows exactly matched before/after |
| Public deployment / actual payments | NOT RUN | Explicitly outside task; no charge or collected-cash state was created |
| Owner personal checkout test | NOT RUN / PENDING | Follow [MANUAL-CHECKOUT-GUIDE.md](MANUAL-CHECKOUT-GUIDE.md) |

## Real MySQL and browser evidence

- [MySQL verification JSON](ADDRESS-CHECKOUT-MYSQL-EVIDENCE.json): generated customer IDs 5/6; order IDs 1/2/3; direct SQL totals/item counts and real HTTP/concurrency outcomes. Only synthetic fixtures were inserted; their brands were deactivated after verification so they do not clutter discovery. No existing customer/product was overwritten.
- [Browser verification JSON](ADDRESS-CHECKOUT-BROWSER-EVIDENCE.json): the complete successful Edge flow created **order #4**, two brands, PKR **6250.40**, PLACED/COD/UNPAID.
- [Restart verification JSON](ADDRESS-CHECKOUT-PERSISTENCE-EVIDENCE.json): order #1 from API testing and #4 from browser testing survived MySQL plus backend restart with identical snapshots/items/inventory. Direct rows show demo shirt stock 18 and tote stock 19 after the single browser purchase.
- [Desktop confirmation](screenshots/checkout-confirmation-desktop.png), [mobile order](screenshots/checkout-order-mobile.png), and checkout review screenshots: [360](screenshots/checkout-review-360x800.png), [390](screenshots/checkout-review-390x844.png), [768](screenshots/checkout-review-768x1024.png), [1366](screenshots/checkout-review-1366x768.png), [1920](screenshots/checkout-review-1920x1080.png).

Screenshots are actual captures of the running application. Desktop confirmation and mobile receipt were visually inspected. No mock response or browser route interception was used for this manual-style journey. Unit tests use normal isolated mocks, including the intentionally simulated lost response; these are not live paths.

The first real-browser run **failed** at submission: CORS omitted the new Idempotency-Key header, so the browser correctly displayed an uncertain-result retry state. SecurityConfiguration now explicitly permits that header for configured origins, with an allowed-origin/denied-origin regression test. After restart, actual preflight returned 200 with the required header and the entire browser flow passed. That earlier failure is not reported as a successful checkout.

Other initial verification failures were corrected: the restricted sandbox could not compile from the existing Maven cache, so compilation ran with approved normal-user access; frontend nullable instructions typing was corrected; an old disabled cart control was replaced and its regression assertion updated. Final application/tests/builds above passed.

## Commands actually used

Backend root, Java 21 and host Maven cache:

```powershell
$env:JAVA_HOME=Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent
$env:MAVEN_OPTS='-Duser.home=C:\Users\DELL'
.\mvnw.cmd -o test
.\mvnw.cmd -o '-Dtest=CheckoutIntegrationTests,AuthorizationIntegrationTests' test
.\mvnw.cmd -o package '-DskipTests'
.\scripts\seed-m01-catalogue.ps1
node scripts/verify-checkout-mysql.mjs
node scripts/verify-checkout-browser.mjs
node scripts/verify-checkout-persistence.mjs before
# Restart only the acceptance Java process and brandista-m01 MySQL; preserve volume.
node scripts/verify-checkout-persistence.mjs after
docker compose --env-file .env.m01 -p brandista-m01 config --quiet
```

Frontend commands in `target/m01-frontend`, matching the sibling source/config/lockfile: `npm.cmd run build`, `npm.cmd test -- --maxWorkers=1`, `npm.cmd run lint`. The original repository's native dependency lock is avoided by using that source copy. Playwright was installed only under ignored `target/browser-qa` and used the existing Edge installation; it is not a production dependency.

## Runtime and data safety

Current storefront: **http://127.0.0.1:5176/**. Addresses: `/account/addresses`; checkout: `/checkout`; history: `/orders`. Backend: **http://127.0.0.1:8082/api/v1**; readiness: **http://127.0.0.1:8082/actuator/health/readiness**. Database: MySQL 8.4.11 on loopback 3308, schema `brandista_m01`, named volume `brandista-m01_mysql_data`.

MySQL, the updated Java backend and the frontend remain running in background processes at handoff. The existing original 3307/8080 stack and earlier synthetic/H2 artifacts were not repurposed or deleted. Existing real owner/customer data in the acceptance database was preserved. A private pre-V9 SQL backup and previous jar are under ignored `target/checkout-backup`; do not restore them over newly created orders without a reviewed migration/recovery plan. No down-migration or destructive reset was used.

Raw logs are under ignored `target/address-checkout-*.log`. Generated test credentials/session tokens remain only in ignored local test files under target; committed evidence contains no credentials or hashes. Owner credentials are neither requested nor pre-filled. The original frontend manifests/lockfile remain unchanged.

## Files and remaining boundaries

Backend: V9 migration; existing address DTO/entity/service/controller and user default field; product stock/DTO/locking/ADMIN inventory endpoint; customer/cart/product locking queries; new order entities/repository/DTO/service/controller; safe conflict handling; CORS allowlist; local COD configuration and existing startup/seed scripts. Checkout/default/concurrency/rollback/CORS tests were added and the migration-count assertion updated.

Frontend: new `checkout.tsx`, `checkout.css`, `checkout-api.ts`, `checkout-types.ts`, `checkout.test.tsx`; existing routes/cart/account/header/product stock display/types/tests/docs connected. Existing Brandista design and API client are retained. No native app was started.

Deliverables: [SRS addendum](../requirements/ADDRESS-CHECKOUT-SRS.md), [API contract](../api/CHECKOUT-CONTRACT.md), this acceptance report, [manual guide](MANUAL-CHECKOUT-GUIDE.md), repeatable verification scripts, JSON evidence and screenshots.

Remaining intentional limits: COD is unpaid until an actual collection-confirmation workflow is approved/implemented; there is no fulfilment, cancellation/restock, online gateway, fee engine or split shipment. Public launch policy/rate limits and the missing historical full SRS are outside this local feature scope. The next action is for the owner to personally complete the new manual checkout checklist; no deployment approval is requested.
