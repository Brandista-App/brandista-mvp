# PR7A — merchant order APIs and operations

## Discovery and capability matrix

Started from clean synchronized official Brandista-App backend main e103c079023e00ae36ab2325138089f650afc743 and admin main7f1e88e31d8f1cd29a91a4d6ffb58f8174bfcb89 under D:\Brandista Workplace. The admin has assigned-brand catalogue/inventory but no orders. brandista-docs has no tracked documents. The backend SRS index points to the approved address/checkout addendum; the original full SRS is absent. The older F1.0 pilot runbook predates checkout and is historical.

| Capability | Before PR7A | Change |
|---|---|---|
| Customer checkout | GET /customers/me/checkout/config; POST /customers/me/checkout/preview; POST /customers/me/orders | Preserve API; atomically initialize brand fulfillment |
| Customer history/details | GET /customers/me/orders and /orders/{id}; owning CUSTOMER only | Preserve existing response and snapshots |
| Mixed-brand orders | Supported; immutable brand/product/price and delivery snapshots | One financial order, independent order/brand fulfillment |
| Merchant list/detail/filter/actions | Missing | Three routes below |
| Order transitions | None; V9 fixes customer status to PLACED | Separate strict brand fulfillment state machine |
| Payment/inventory | COD/UNPAID/PKR; zero charges/tax/discount; deduct stock on placement | Unchanged by merchant processing |
| Merchant authorization | Enabled account + active membership + ACTIVE brand, current DB checks | Reuse; no super-admin/legacy-admin tenant bypass |
| Fulfillment audit | Missing | Atomic append-only per-brand transition events |

Backend changes required: **YES**. Implement backend PR7A and stop for review/merge before admin order UI work. Customer web/mobile and admin source are unchanged. This PR extends the checkout addendum's explicitly deferred fulfillment scope.

## Domain and financial boundaries

customer_orders remains the immutable customer purchase/financial record. Its PLACED status records submission, not aggregate shipment state. COD remains UNPAID even when merchants mark delivery: DELIVERED is fulfillment attestation, not payment collection, carrier integration or settlement.

V14 adds one merchant_order_fulfillments row per immutable (order_id,brand_id). All that brand's lines move together. Current product reassignment cannot transfer historical visibility. Brand amounts are **itemsSubtotal**, the BigDecimal sum of only that brand's snapshot line subtotals. They are not whole-order totals, payable balances, cash-to-collect or payouts. No shared-fee allocation is invented.

Only adjacent transitions are allowed: **PLACED -> ACCEPTED -> PACKED -> SHIPPED -> DELIVERED**. No skips, reverse transitions, repeated terminal actions, cancellation, returns, rejection/restocking, refund, payment updates, partial line shipments, packages, tracking or courier booking. Exceptions require another reviewed workflow; do not edit tables as a workaround.

## HTTP contracts

Base: /api/v1/merchant/brands/{brandId}/orders. All calls require private bearer authentication and a current active assignment to the route's ACTIVE brand. Use existing GET /api/v1/merchant/brands for brand selection.

| Method/path | Request | Result |
|---|---|---|
| GET base | page=0, size=20 (1–100), optional status enum | 200 Page |
| GET /{orderId} | Positive numeric route IDs | 200 Detail |
| POST /{orderId}/fulfillment | status, expectedVersion | 200 updated Detail |

Example JSON: `{"status":"ACCEPTED","expectedVersion":0}`. Always read the current version and availableTransitions first; do not hardcode version0.

Summary fields: orderId (existing order reference), brandId, createdAt, fulfillmentStatus, version, currency, itemsSubtotal, lineCount, availableTransitions (next adjacent state, empty for DELIVERED).

Page fields: content (Summary[]), page, size, totalElements, totalPages. SQL filters tenant and optional status before counting/pagination; order is createdAt DESC, orderId DESC. An assigned brand without orders gets an empty page. Counts reflect only its selected status. Pagination does not promise a frozen snapshot across new checkouts.

Detail fields: order (Summary), deliveryAddress (existing recipient/phone/address/city/province/postcode/instructions snapshot), items (existing CheckoutModels.Line fields, only this brand), timeline (fromStatus, toStatus, version, occurredAt; only this brand, ascending version). Lists omit delivery PII. Details omit customer account ID/email, idempotency key, request hash, global totals/payment state, other brands' lines/prices/events and internal audit actor IDs.

Amounts use decimal PKR; the future UI must use its existing shared two-decimal formatter and label itemsSubtotal accurately. Timestamps retain existing UTC LocalDateTime conventions. Existing customer response fields remain unchanged.

| HTTP | Meaning |
|---|---|
| 400 | Invalid enum, missing/invalid version, invalid ID or pagination |
| 401 | Missing/invalid/expired bearer or disabled current account |
| 403 | Missing/revoked membership or inactive brand; forged JWT authority is ignored |
| 404 | No fulfillment for the authorized order/brand, including foreign-only order IDs |
| 409 | Stale version, repeat, skipped/backward/terminal transition or unsupported payment policy; reload |

Actor identity comes only from authentication. Body userId/brandId/prices/payment fields cannot select targets or mutate money. A multi-assigned merchant must still select one route brand. Platform authority never substitutes for membership.

## Concurrency and auditing

READ_COMMITTED transactions lock the composite fulfillment row. Authority is checked again after waiting, so committed revocation while waiting is observed. A conditional version update rejects stale submissions; same-version races produce one success and one409. Brand slices have independent rows/versions. Detail reads lock their slice to keep summary/version and timeline coherent. Already-authorized in-flight operations are not cancelled by a later revocation, matching existing merchant conventions.

merchant_order_events is the operational audit/timeline: numeric order/brand/actor IDs, closed from/to states, resulting version and UTC timestamp. Unique order/brand/version and DB transition constraints prevent duplicate/invalid event states. Successful transition and event commit together; audit insert failure rolls back the mutation. No PII, credentials, tokens, payment data or free text is stored. There is no mutable event entity or audit mutation API. Rejected transitions create no successful transition event; existing security audit behavior is unchanged.

Production runtime should have INSERT/SELECT only on merchant_order_events, without UPDATE/DELETE/DDL or overriding broad grants. Follow PR2 observer separation and immutable export/retention guidance. DBA tamper resistance is not claimed from application controls. Treat fulfillment addresses as personal data, restrict access/logging, and apply an approved retention policy.

## Migration and rollout

V1–V13 are unchanged. V14 creates fulfillment/events tables and tenant query indexes. It backfills DISTINCT historical order/brand pairs as PLACED/version0 using original order creation times. It fabricates no historical action/actor. Existing commerce/accounts/grants/invitations/security audits remain unchanged. Checkout inserts brand rows after order/item flush in its existing stock/cart transaction. Replays do not duplicate rows; failures roll back the full checkout.

Use an approved migration identity, backup and recovery plan. **Quiesce checkout writes during V14 and upgrade every backend writer before resuming.** Old writers creating orders after the one-time backfill would omit fulfillment. Mixed-version rolling checkout writers are unsupported. Verify exact snapshot brand-pair coverage and preserved financial rows before exposing merchant processing. Do not roll back and continue old checkout writers without a reviewed forward repair. Do not drop new fulfillment/audit history as rollback.

The persistent PR5/PR6 acceptance database is not migrated, queried or mutated by PR7A development. Its running service retains its existing artifact. Final Maven packaging uses a separate tracked-source export to avoid replacing that running JAR.

## Verification and next milestone

Java21: `./mvnw.cmd --batch-mode --no-transfer-progress verify` runs full H2 backend/customer regressions and new order/migration tests. `./scripts/verify-admin-pr7a-mysql.ps1` requires PowerShell7/Docker; creates one new disposable MySQL8.4.11 container, ephemeral loopback port, separate verify/upgrade schemas, synthetic fixtures and process-only random credentials; tests populated V13->V14 preservation and merchant order authorization/finance/audit/concurrency; removes only its own container and anonymous volume. No existing datasource/volume is reused. No dependency was added; no backend dependency-audit plugin is configured.

After backend review/merge, implement the admin order UI against these contracts: reuse auth/API client, assigned brands, design/components, PKR formatter, pagination/loading/error states; confirm actions, send expectedVersion, reload on409, clear sessions on401, handle revoked access403. Never imply payment collected. Frontend checks belong to that later PR; no frontend is implemented or tested here. A later live acceptance migration and synthetic order placement require explicit environment authorization; the retained acceptance DB is not silently reused.
