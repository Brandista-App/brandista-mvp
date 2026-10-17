# Admin PR 1: privileged identity and revocation foundation

## Model and permission matrix

Keep `users.role` (`CUSTOMER`/`ADMIN`) and existing registration/login/JWT wire formats unchanged. Platform privileges are separate, explicit `user_platform_grants` records. The only platform grant is `SUPER_ADMIN`; future brand administration must use brand-scoped memberships, never this global grant table. A CUSTOMER can hold a platform grant while retaining normal customer shopping access. Legacy ADMIN is not SUPER_ADMIN.

| Enabled account | Customer commerce | Existing catalogue/review administration | Super-admin identity |
| --- | --- | --- | --- |
| CUSTOMER, no grant | Yes | No | No |
| Legacy ADMIN, no grant | No (existing contract) | Yes | No |
| CUSTOMER + active SUPER_ADMIN grant | Yes | Yes | Yes |
| Legacy ADMIN + explicitly active SUPER_ADMIN grant | No (existing contract) | Yes | Yes |
| Disabled/deleted/invalid subject | No bearer authentication | No | No |

`GET /api/v1/super-admin/identity` returns only the authenticated subject ID and effective SUPER_ADMIN capability. It is read-only and protected by both URL and method security. Existing management controllers accept ADMIN or SUPER_ADMIN; super-admin authority is not implemented by pretending to be legacy ADMIN.

## Authentication and revocation

Existing JWT signature, HS256 algorithm, issuer, audience and lifetime checks remain unchanged. After cryptographic validation, every bearer request resolves the current account and grants from the primary database in a new read-only transaction. No authority cache or token-claim fallback exists. `role`, `roles`, `scope`, `grants` and client-submitted identifiers cannot assign authority. The signed `sub` is the identity and must identify an enabled existing account.

Disabled/deleted accounts and malformed/unknown subjects receive 401. An enabled account lacking the required current permission receives 403. Revoking a platform grant removes SUPER_ADMIN authority on the next authentication after revocation commits, even with the same unexpired JWT. Legacy ADMIN demotion likewise removes its catalogue authority. Revoking a SUPER_ADMIN grant does not revoke an independent legacy ADMIN base role: demote or disable that account to remove legacy catalogue access too.

An already-authorized in-flight operation is not cancelled. Database availability is required; failures do not fall back to JWT privilege claims. A new explicit grant becomes effective for valid identity tokens on subsequent requests; step-up/MFA and grant-issuance session controls belong to the future controlled provisioning milestone. There is no grant-issuance endpoint in this PR.

## Registration and request authorization

Public registration still creates only CUSTOMER. Extra JSON role/grant/ID fields remain ignored by the existing DTO contract and cannot change another account. Login response fields and the role claim remain backward compatible with Android/web clients. Customer cart/address/checkout/order contracts and implementation are unchanged. Disabled bearer tokens are now rejected centrally on all authenticated requests, including requests sent to public resources with an Authorization header.

Only known public routes, existing catalogue/review management namespaces, customer namespaces and the new super-admin namespace are allowed. Other routes default to denial, including unimplemented account/role/grant mutation APIs. Authenticated unknown URLs now return 403 instead of the former pass-through 404. Tests use explicit allowed namespaces without adding production test-only exceptions. Method authorization provides a second enforcement layer. Container ERROR dispatches are permitted only to render original validation/error responses (including checkout 400/500); an external REQUEST to `/error` is still denied. Clients cannot select the servlet dispatcher type with a header or URL.

## Additive migration and operation

V10 creates `user_platform_grants` with a restricted user foreign key, unique `(user_id, grant_type)`, allowed-type check, creation/revocation timestamps and optimistic version. It contains no user UPDATE, grant backfill, seed account, password or credential. V1-V9 are untouched. V10 must exist before this application version handles bearer requests. All application instances must run the new resolver before revocation can be guaranteed across the deployment; drain/restrict legacy admin traffic during a mixed-version rollout. Authorization reads must use the authoritative database, not a lagging replica.

No public super-admin signup, grant-write/role-write endpoint, operator bootstrap, credentials, brand memberships, invitation flow, portal or production grant is added. Test fixtures create isolated synthetic grants only in test databases. Production grant issuance/reinstatement requires a later audited provisioning workflow; this document intentionally provides no ad-hoc production privilege INSERT procedure.

Take the normal protected database backup before deployment. Keep the additive table on application rollback; do not delete users or run destructive down migrations. Rolling back to the previous binary also removes immediate-revocation enforcement, so disable/restrict administration during rollback rather than silently accepting that security regression. Re-review existing legacy ADMIN accounts before rollout; no automatic promotion occurs.

## Verification

`./mvnw verify` runs the full available backend suite, including existing customer commerce tests. New tests cover public registration/login and submitted identifiers, customer/legacy-admin denial, explicit grants with customer access, method-protected catalogue writes, same-token revocation, disablement, legacy-role demotion, deleted accounts, elevated claims without grants, invalid subjects, signature tampering and forbidden mutation attempts. Direct method-security invocation is tested independently of HTTP URL matching.

The migration test upgrades a populated V9 H2/MySQL-mode database to V10 and checks customer/ADMIN preservation, zero automatic grants, uniqueness, foreign keys and rejection of a global BRAND_ADMIN grant. H2 tests do not establish full MySQL runtime equivalence. `./scripts/verify-platform-grant-migration.ps1` separately verifies V1-V9 SQL followed by V10 on disposable MySQL 8.4.11, user preservation and constraints. It publishes no ports, uses no existing volumes, generates an ephemeral container password, and removes its container. This is a SQL compatibility check, not a full MySQL HTTP test run. Results are written to ignored `target/mysql-migration-check.json`. CI runs Java 21 full Maven verification without deployment credentials.

Security framework references: https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html and https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/method-security.html
