# PR3: brand memberships and merchant tenant isolation

## Authorization policy

A brand administrator is an existing enabled user with an explicit, non-revoked
row in `brand_admin_memberships`. The user's base CUSTOMER/ADMIN role is unchanged.
One user may have several explicit memberships and a brand may have several
administrators. No membership is inferred from an email, JWT role/brand claim,
brand application contact, legacy ADMIN role, or public registration field.

Existing ACTIVE brand records are the approval boundary: only platform-controlled
global administration/application approval creates or approves them. There is no
new public brand signup or invitation acceptance in this PR.

| Capability | CUSTOMER without membership | Explicit active brand member | Legacy ADMIN | Explicit SUPER_ADMIN |
| --- | --- | --- | --- | --- |
| Existing customer APIs | Existing CUSTOMER policy | Existing CUSTOMER policy | Existing base-role policy | Existing base-role policy |
| Merchant routes | Denied | Assigned ACTIVE brands only | Explicit membership required | Explicit membership required |
| Existing global catalogue/category/application-review routes | Denied | Membership adds no global authority | Existing global access preserved | Existing global access preserved |
| Membership grant/revoke service | Denied | Denied | Denied | Current enabled account + current platform grant required |

SUPER_ADMIN/global administrators continue using `/api/v1/admin/...` for platform
operations; merchant routes never imply arbitrary tenant membership. Granting a
legacy ADMIN a membership does not remove its independent pre-existing global
access. Use CUSTOMER identities for administrators who must be tenant-restricted.

Members may read all product states in their assigned ACTIVE brand, create DRAFT
products, update editable products, publish/unpublish, deactivate, and modify
nonnegative stock. Existing product/category activation and publication rules
remain in `ProductService`/`Product`; DEACTIVATED products cannot be edited,
republished or have stock modified through merchant routes. Members can select an
existing active global category but cannot administer categories. Changing a
product's brand is always forbidden through merchant routes, even if the caller
administers both brands. Global administrator reassignment remains unchanged.

All merchant operations, including reads, are denied for inactive brands. Assigned
brand lists contain only ACTIVE, currently assigned brands; a caller with none
receives 403. Suspending a brand does not erase membership history. Reactivation
restores access for still-active memberships. Platform staff can revoke membership
for an inactive brand or disabled target user.

## HTTP contracts

All routes require a valid bearer JWT. The existing authentication converter checks
the current enabled account; the merchant service independently rechecks enabled
status, current membership, active brand and product ownership in the database.
No authoritative merchant permissions are added to JWTs or cached.

| Method | Route | Request / response |
| --- | --- | --- |
| GET | `/api/v1/merchant/brands` | Array of assigned active brands: `id`, `name`, `slug`, `logoReference` |
| GET | `/api/v1/merchant/brands/{brandId}/products` | `page=0`, `size=50` (1-100); existing `ProductPageResponse` |
| GET | `/api/v1/merchant/brands/{brandId}/products/{id}` | Existing `ProductResponse` |
| POST | `/api/v1/merchant/brands/{brandId}/products` | Existing `ProductCreateRequest`; 201 + `ProductResponse` |
| PUT | `/api/v1/merchant/brands/{brandId}/products/{id}` | Existing `ProductUpdateRequest`; `ProductResponse` |
| PATCH | `/api/v1/merchant/brands/{brandId}/products/{id}/publish` | Existing publication rules; `ProductResponse` |
| PATCH | `/api/v1/merchant/brands/{brandId}/products/{id}/unpublish` | Existing publication rules; `ProductResponse` |
| PATCH | `/api/v1/merchant/brands/{brandId}/products/{id}/deactivate` | Existing terminal deactivation; `ProductResponse` |
| GET | `/api/v1/merchant/brands/{brandId}/products/{id}/stock` | `{ "productId": 123, "stockQuantity": 12 }` |
| PUT | `/api/v1/merchant/brands/{brandId}/products/{id}/stock` | `{ "stockQuantity": 12 }`; same stock response |

Create/update bodies retain the existing validated fields (`name`, `slug`, optional
`description`, `brandId`, `categoryId`, `price`, `availability`). Body `brandId` must
equal the route's authorized brand. Actor identity comes only from authentication;
extra `userId`, `id`, role/grant or tenant query fields cannot select another actor,
product or tenant. Lists are filtered in SQL before pagination and counts.
Product slugs remain globally unique under the existing catalogue contract.

Unauthenticated/disabled bearer requests return 401. An enabled caller lacking an
active membership/brand receives 403. A foreign or nonexistent product ID under an
otherwise authorized brand returns the same 404, without revealing the foreign
product. Body brand mismatch returns 403. Existing 400 validation and 409 product
rule/duplicate conflicts are retained. Public published-product discovery remains
public under its existing rules; private merchant catalogue/inventory access is
the tenant boundary introduced here.

The facade checks authorization before delegating to shared business rules. A
composite `(productId, brandId)` lookup is used for every product operation. Mutations
hold a pessimistic product-row lock to prevent a concurrent global reassignment
between ownership check and mutation. Ordinary reads do not take write locks.
Checkout and merchant stock changes serialize on product rows.
Membership changes committed before a new request take effect with the same
previously issued JWT. An already-authorized in-flight transaction is not cancelled.
Read Committed transactions avoid stale membership snapshots across service calls.

## Membership lifecycle and audit

`BrandMembershipService.grant(userId, brandId)` and `.revoke(userId, brandId)` are
internal Spring application-service entry points with method authorization and an
independent current-DB SUPER_ADMIN check. There is deliberately **no HTTP grant,
revoke, self-assignment, invitation or membership-management endpoint**. A future
approved platform workflow can call this service; do not bypass it with ad-hoc SQL.
This PR does not provide an operator provisioning UI/CLI for brand memberships and
does not assign any real/demo users. PR4 will handle invitation acceptance.

Lifecycle writes serialize using PR2's security-state lock, recheck the actor after
locking, and enforce unique `(user_id, brand_id)`. Grants require an enabled target
and ACTIVE brand. Re-grant clears `revoked_at` and increments version without
creating another membership row; original creation time remains. Repeat active
grants/revocations return `changed=false` with a safe reason. Revocation does not
change the user's customer role, platform grants or other brand memberships.

Successful grant/revoke and their audit event commit atomically; audit insert
failure rolls back the membership mutation. Authorized lifecycle rejections are
audited. A stale SUPER_ADMIN security context rejected by the current-DB check
records a rejection independently of rollback. Pre-method authorization denial
does not invoke the lifecycle service. All event payloads use the existing closed
operation/outcome/reason design, numeric target user and new numeric target brand,
UTC time and authenticated actor. No credentials, emails, JWTs or arbitrary request
metadata are recorded. The PR2 append-only authorization/DB-permission/retention
guidance still applies; grant the runtime identity only INSERT/SELECT on audit data.

## Additive migration and rollout

V12 creates `brand_admin_memberships` with user/brand foreign keys (`ON DELETE
RESTRICT`), unique `(user_id, brand_id)`, `created_at`, nullable `revoked_at`, version
and a brand/active lookup index. Active means `revoked_at IS NULL`; there are no
automatic memberships or user-role updates. It adds nullable `target_brand_id` to
`security_audit_events`, with no history rewrite or cascading FK. V1-V11 remain
unchanged. Existing users, brands, products, stock, addresses, carts, orders,
applications, platform grants and audit events are preserved.

Apply V12 through the approved Flyway deployment/migration identity before serving
this binary, because all audit appends now include the nullable brand column.
Take the normal protected backup. A compatible PR2 binary can continue using the
additive schema on rollback, but the merchant routes are unavailable in that binary.
Do not drop tables/history or run destructive down migrations. Reads used for
authorization must reach the authoritative database rather than a lagging replica.

## Verification

With Java 21 configured:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

If a locally running JAR is locked by Windows, run verification from a separate
clean checkout/build directory instead of replacing or stopping that application's
artifact. Tests use isolated H2 schemas and synthetic users only. The new suite
covers tenant reads/writes/publication/stock, multiple memberships, same-JWT
revocation, disabled users, inactive brands, forged claims/identifiers, service
authorization, legacy/global compatibility, audit redaction/rollback and concurrent
grants. The full suite includes existing registration/login/cart/address/inventory,
COD checkout and order behavior.

`./scripts/verify-admin-pr3-mysql.ps1` requires Docker and Java 21. It creates a new
disposable MySQL 8.4.11 container with a localhost-only random port, ephemeral secret
and no existing volumes. It verifies V11-to-V12 preservation of populated commerce
and security data, zero membership backfill, and executes merchant integration
tests against a separate empty schema. It removes its container and restores test
environment variables afterward. Never point this verification at a deployment
database. Test evidence is written under ignored `target/`.

No frontend, invitation acceptance, fulfilment, promotion, CMS, public signup or
Android redesign is included. Fine-grained staff roles, tenant order operations,
membership-management UX and invitation/session assurance remain future work.
