# PR6B: restricted merchant access and ordinary-account lifecycle

## Authorization and preparation

These are authenticated operator APIs, not public provisioning or self-service
interfaces. Use HTTPS in deployments and a privately held bearer session for an
enabled, explicitly granted SUPER_ADMIN. Authorization comes from the database
on every request and is checked again after acquiring the security-state lock.
JWT role/tenant claims do not grant privileges. Customers, merchants and legacy
ADMIN identities cannot operate these routes. No frontend is supplied.

Before a live operation obtain approval for its environment, target and purpose.
Independently verify the deployment artifact, JDBC host/schema, connection identity
and intended environment through protected deployment configuration. Authenticate
privately and verify `/api/v1/super-admin/identity`. Never record Authorization
headers, login bodies, passwords, invitation tokens, configuration or SQL parameters.
Use a secure operator client; do not paste bearer tokens into shell history.

Verify the target's numeric user ID and normalized email against approved identity
records and a read-only observer lookup of `users` (`id,email,role,status` only).
For membership operations independently verify the numeric brand ID, brand identity
and the exact `(user_id,brand_id)` membership. There is no new account-search or
grant endpoint. ID plus email prevents accidental targeting; it is not proof of
account ownership. Resolve identity disputes out of band before acting.

## Supported contracts

All requests are POST with JSON `{"expectedEmail":"<verified target email>"}`.
Use actual approved numeric IDs in paths. Success is **204**, with no account data
or credentials in the response.

| Route | Effect |
| --- | --- |
| `/api/v1/super-admin/users/{userId}/brand-memberships/{brandId}/revoke` | Revoke only this active membership through `BrandMembershipService`; increment its version, preserve other memberships and customer access. Works for disabled targets and inactive brands. |
| `/api/v1/super-admin/users/{userId}/disable` | Change an enabled ordinary CUSTOMER to DISABLED; preserve passwords, base role, memberships and history. |
| `/api/v1/super-admin/users/{userId}/restore` | Change an eligible disabled ordinary CUSTOMER to ENABLED; preserve all membership revocations and versions. |

Account operations reject the actor, legacy ADMIN accounts and **every account
with any current or historical platform grant**, even revoked grants. Consequently
this API cannot disable the last custodian, another custodian, or restore a former
custodian. Use the existing separately approved PR2 offline break-glass process
for platform custody; PR6B does not expand it or add privileged-account restore.
Ordinary accounts without memberships are eligible. Repeats are explicit 409
conflicts, not silent successes. An absent membership is also a conflict.

| Status | Meaning |
| --- | --- |
| 400 | Invalid/missing identity body or nonpositive numeric path ID. |
| 401 | Missing/invalid/expired token, disabled or nonexistent authenticated account. |
| 403 | Authenticated caller lacks current SUPER_ADMIN authority; revoked merchant membership on merchant routes. |
| 404 | Operator-authorized target user or brand does not exist. |
| 409 | Identity mismatch, protected account, incompatible/repeated state or absent/revoked membership. Recheck approved state before retry. |

Operator rejection messages deliberately omit email, grant and account details.
No target lookup happens for unauthorized callers. Existing generic request
validation and access-denied handling remain in effect. Database/audit failure
fails closed; do not retry blindly after an uncertain response. Verify state and
audit using a read-only observer first. An already-authorized in-flight request
is not cancelled; requests after committed disable/revocation see current state.

## Recovery semantics and incident handling

Disable rejects normal login and existing bearer tokens (401). Restore permits
normal login and restores access to **still-active** assignments on ACTIVE brands.
Revoked memberships remain revoked (403). No membership or platform grant is
created by restore. Account status suspension is **not token/session revocation**:
an unexpired pre-disable JWT can authenticate again after restore. Password reset
also does not invalidate existing JWTs. Do not restore a compromised identity
until session risk is resolved; wait for all existing JWT lifetimes to expire
under the deployed TTL policy, or use a separately reviewed session-revocation
capability. Review active memberships before restore; revoke unwanted assignments
explicitly first. PR6B adds no credential reset, token blacklist or new grant path.

To recover a revoked merchant assignment, retain PR4 onboarding: independently
verify the approved application's contact and account control, then issue a fresh
invitation **after** revocation. Revoke/reissue any outstanding stale pending
invitation via supported PR4 controls, deliver privately, and have the intended
enabled customer accept. An accepted invitation cannot be reissued; create a new
invitation from the approved application. Membership-version fencing prevents
stale invitation acceptance from undoing revocation. Do not invoke the internal
grant service or edit security tables as an operator workaround. Brand activation
continues to enforce PR4 proof/version checks; PR6B does not recreate onboarding.

## Transactions and audit verification

The existing singleton security lock serializes these operations with PR2 custody,
PR3 membership and PR4 acceptance mutations. Target identity is locked/verified
inside the same READ_COMMITTED transaction; account state transitions are
conditional and repeated/concurrent identical requests yield one success and one
conflict. Successful state changes and audit insertion commit atomically; audit
failure rolls back the change. Policy rejections return normally from the service
so their audit rows commit before HTTP status translation. Database-authority
rejections after lock acquisition use the independent rejected-attempt writer.

Observe `security_audit_events` using a restricted read-only identity:
`actor`, `operation`, numeric `target_user_id`, `target_brand_id`, UTC `occurred_at`,
`outcome`, `reason` and ordered event `id`. Operations are
`BRAND_MEMBERSHIP_REVOKED`, `ORDINARY_ACCOUNT_DISABLED`,
`ORDINARY_ACCOUNT_RESTORED`. Success reason is COMPLETED; rejected reasons include
IDENTITY_MISMATCH, NOT_FOUND, NOT_GRANTED, PROTECTED_ACCOUNT and STATE_CONFLICT.
Unauthorized authenticated super-admin-route attempts use existing
PRIVILEGED_ACCESS / ACCESS_DENIED auditing without trusting client target IDs.
Malformed requests fail validation before lifecycle invocation and do not create
lifecycle audit events. Database outages can prevent durable failure recording;
investigate protected infrastructure telemetry. No arbitrary reason text is stored.

No schema migration is needed: existing audit columns accommodate the new closed
operation/reason enums. No audit mutation API/entity exists. Apply PR2 runtime
INSERT/SELECT-only audit permissions and immutable export/retention guidance.

## Verification and later isolated acceptance

Run Java 21 `./mvnw.cmd --batch-mode --no-transfer-progress verify` for all H2
integration/customer regressions. Run `./scripts/verify-admin-pr6b-mysql.ps1` with
PowerShell 7 and Docker for the same lifecycle integration tests against a new
disposable MySQL 8.4.11 schema. It generates process-only credentials, binds an
ephemeral loopback port, applies Flyway V1–V13, creates synthetic test fixtures,
and removes its own container/anonymous volume. It never reads persistent
acceptance secrets, uses existing volumes or targets the PR5/PR6 database.

After review/merge, obtain approval for a separate live acceptance run. Independently
confirm isolation and synthetic-only data before any mutation. Capture approved
user/brand IDs, account state, membership status/version and audit checkpoint.
Privately log in the synthetic merchant, retain its old JWT securely, verify
assigned and foreign brand reads/writes, revoke one assignment, then repeat with
the same token: revoked brand 403, other assigned brand/customer APIs unchanged.
Disable through the endpoint: login and old-token authenticated APIs 401. Restore
through the endpoint: ordinary login succeeds, revoked membership remains denied,
still-active assignment works, no platform grant appears. Confirm super-admin
identity and onboarding regression, protected custodian conflicts, final intended
state and audit records. Do not erase evidence or directly modify security tables.
No persistent PR5/PR6 acceptance mutation is part of implementation verification.
