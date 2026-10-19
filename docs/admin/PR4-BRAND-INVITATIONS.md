# Secure brand invitations and onboarding (Admin PR4)

## Lifecycle and identity policy

The existing public application form and `POST /api/v1/brand-applications` remain unchanged. Review uses the existing `PATCH /api/v1/admin/brand-applications/{id}/review` contract (`decision`, `reviewNote`). APPROVED now requires an enabled account with a current, explicit SUPER_ADMIN platform grant. Legacy ADMIN reviewers may request information or reject; their role alone cannot approve, invite or activate.

Approval locks the application, creates one unique-slug INACTIVE brand and links it to the application in the same transaction. Repeat/concurrent approval returns 409 and creates no additional brand. A conflicting existing storefront slug also returns 409 for operator resolution; the service deliberately does not infer ownership of a similarly named existing brand. There is no arbitrary application-to-brand linking endpoint.

An invitation is bound to the **normalized approved application's contact email**, application and brand. Operators cannot substitute a recipient, user ID or tenant in the request. Acceptance requires both the invitation secret (proof of access to delivery) and an authenticated, enabled customer account with that same email. There is no public invitation lookup or token-status endpoint. Unknown, expired, revoked, consumed and wrong-email tokens all return the same 400 message. No unauthenticated request can probe token validity.

For an existing customer, log in normally and accept: the CUSTOMER role, password, addresses, cart and orders remain intact. For a new identity, use the existing `POST /api/v1/registrations` with `email` and `password`, then `POST /api/v1/auth/login` and accept with the returned access token. Existing password validation and duplicate-email rules apply. An invitation does not reset a password, verify a different email, create an account automatically, or grant SUPER_ADMIN. Recover an existing account through the separate established account-recovery/operator process before accepting. Never send temporary passwords.

Acceptance creates or explicitly reactivates one `(user, brand)` membership, consumes the token, and writes audit records atomically. It does not activate the brand. A membership on an INACTIVE brand cannot use merchant catalogue/stock APIs. The super-admin must inspect onboarding and explicitly activate. Eligibility requires an APPROVED application plus an accepted invitation linked to a still-active membership at its accepted version and an ENABLED account. The existing global brand-update API also enforces this gate when setting application-approved brands ACTIVE. Existing manually created brands keep their prior lifecycle; V13 does not rewrite records or deactivate current stores.

Revocation remains immediate for previously issued JWTs. Invitations capture the membership version at issue: a token issued before membership revocation cannot undo it. A new explicit invitation issued/reissued after revocation can regrant. A revoked/regranted membership invalidates old invitation evidence for activation until a new invitation is accepted. Removing a merchant does not automatically hide an already active storefront; use the existing explicit brand-deactivation operation when needed. Reactivation of an application-approved storefront requires current onboarding evidence.

## API contracts

All `/api/v1/super-admin/**` operations require a bearer access JWT and **current database-backed SUPER_ADMIN**, never a trusted token role claim alone. Disabled accounts and revoked grants are denied immediately.

| Method and path | Body / result |
| --- | --- |
| POST `/api/v1/super-admin/brand-applications/{id}/invitations` | No body. 201 status object; recipient comes from approved application. |
| GET `/api/v1/super-admin/brand-applications/{id}/invitations?page=0&size=20` | Page `{content,page,size,totalElements}`, size 1–100. |
| POST `/api/v1/super-admin/brand-invitations/{id}/revoke` | No body. 200 REVOKED; accepted/already-revoked returns 409. Expired unconsumed invitations can also be revoked. |
| POST `/api/v1/super-admin/brand-invitations/{id}/reissue` | No body. 201 new status; rotates a pending/expired unrevoked invitation and revokes the prior token atomically. Accepted/revoked returns 409. |
| GET `/api/v1/super-admin/brand-applications/{id}/onboarding` | `{applicationId,applicationStatus,brandId,brandStatus,activationEligible}`. |
| POST `/api/v1/super-admin/brand-applications/{id}/activate` | No body. 200 onboarding state, or 409 if incomplete. |
| POST `/api/v1/brand-invitations/accept` | Authenticated recipient only, JSON `{"token":"<secret supplied privately>"}`. 200 `{invitationId,brandId,membershipId}`; generic 400 rejection, 429 quota exceeded. |

Status objects contain `id`, `applicationId`, `brandId`, `recipientEmail`, `status` (PENDING/EXPIRED/REVOKED/ACCEPTED), UTC `createdAt`, UTC `expiresAt`, nullable `replacesInvitationId` and `acceptedUserId`. They never contain a token, hash, password or mail payload. Nonexistent privileged resources return 404; unapproved/rejected applications and duplicate pending invitations return 409. Missing delivery returns 503 and rolls back the invitation. Unauthorized calls use existing 401/403 responses. HTTPS is required beyond isolated localhost development. Keep request-body/header logging, proxy body tracing and client telemetry disabled/redacted on authentication and acceptance requests. Never put invitation tokens into query strings, URLs, shell literals or tickets.

## Configuration and delivery

| Property | Default / bounds |
| --- | --- |
| `brandista.invitations.ttl` | `PT24H`; 5 minutes–7 days. |
| `brandista.invitations.acceptance-window` | `PT1M`; 10 seconds–1 hour. |
| `brandista.invitations.acceptance-attempts` | `10`; 1–100. |
| `brandista.invitations.dev-delivery-enabled` | `false`. |
| `brandista.invitations.dev-delivery-key-base64` | No default; 32-byte AES key, development only. |
| `brandista.invitations.dev-delivery-directory` | No default; protected directory outside the checkout. |

Quota is per enabled authenticated account, counts successful and rejected attempts, and survives failed transactions/application restarts across nodes in the shared database. One persistent row per account avoids a token/IP keyed unbounded store. Authentication and anonymous traffic still need deployment-level login/IP limits; this change does not replace them. A security singleton lock serializes provisioning, membership changes and onboarding transactions; acceptance is atomic across nodes. Global serialization is suitable for this administrative workflow; monitor lock contention and keep delivery adapters fast and bounded.

There is no email provider in this repository. The default `InvitationDelivery` **fails closed**: production cannot create/reissue invitations until an explicitly configured adapter implements `deliver(id, recipientEmail, rawToken)`. No raw token is returned from production HTTP APIs. A production adapter must deliver only to the supplied verified application email, use trusted provider credentials, HTTPS/private transport, bounded timeouts and redacted telemetry; provider integration and production delivery validation remain deployment work. No real emails are sent by this PR.

For isolated development/test only, the `invitation-dev` profile plus explicit opt-in enables AES-256-GCM encrypted local delivery. It refuses `prod`/`production` profiles even if opted in. Each `.invitation.enc` file is a 12-byte nonce followed by ciphertext and a 16-byte authentication tag. Plaintext is never written to disk/logs or exposed by an HTTP endpoint. Keep the directory ACL restricted to the operator, keep the key in a separate secret manager/session and outside version control, and use synthetic `example.invalid` identities. The in-memory capture adapter exists only in test sources.

Delivery occurs inside the creation transaction. Delivery failure rolls back both new invitation and any reissue revocation, preserving the original pending token. A delivery that succeeds before a later database/audit/commit failure may leave an encrypted orphan message containing an unusable token. Verify committed status before handing off delivery; reissue if uncertain. Do not retry delivery of the old token after reissue. A durable provider/outbox can be added with the production adapter; this PR does not promise exactly-once email delivery.

## Windows / PowerShell operator procedure

Run the following only against an explicitly selected **isolated development** database containing synthetic accounts. Do not copy a live/demo connection string. No account provisioning is performed by these commands. For production, first deploy/configure a reviewed delivery adapter and follow the same API lifecycle over HTTPS with independently authorized accounts.

1. Install Java 21 and PowerShell 7.4+, configure your isolated datasource and existing JWT signing secret securely, then run `./mvnw.cmd verify`. Set `JAVA_HOME` to your Java 21 installation if needed. Normal startup applies additive V13; back up and validate a planned production migration separately.
2. Obtain a current super-admin through the existing [offline operator procedure](PR2-SUPER-ADMIN-OPERATIONS.md). Creating an account and granting a privilege to an existing identity remain separate explicit PR2 operations; this invitation feature does neither. Never grant platform privilege to a customer just to accept an invitation.
3. In a fresh, non-transcribed operator session, set opt-in and paste a randomly generated 32-byte base64 key using a masked prompt (store it securely for the separate acceptance session). No secret literals appear in history:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'invitation-dev'
$env:BRANDISTA_INVITATIONS_DEVDELIVERYENABLED = 'true'
$env:BRANDISTA_INVITATIONS_DEVDELIVERYDIRECTORY = 'D:\BrandistaPrivateDev\invitations'
$secret = Read-Host 'Development delivery AES key (base64, 32 random bytes)' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:BRANDISTA_INVITATIONS_DEVDELIVERYKEYBASE64 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr); $secret.Dispose() }
# Start the backend using your existing isolated development launch procedure.
# Remove session environment secrets after the child application has inherited them.
```

Spring relaxed environment binding removes dashes from each property segment; the names above intentionally use `DEVDELIVERY...` without added underscores. Securely generate/store the key with your secret manager; there is no bundled/default key. Restrict directory ACLs before startup. Do not enable debug/request payload logging or run these steps in a transcript. Clear environment variables and close the isolated process/session after use.

4. Using a trusted API client with masked secrets/redacted history, submit a synthetic public application, review it as APPROVED using the existing review route, inspect the INACTIVE brand, then POST its invitation. No recipient override is accepted. Confirm 201 and list PENDING status. The private directory receives only encrypted delivery files.
5. Register the invited email as CUSTOMER if new; otherwise log into that existing customer account. Keep the access JWT private. In a separate PowerShell 7.4+ session, use the helper to decrypt and accept directly in memory against localhost:

```powershell
./scripts/accept-dev-invitation.ps1 `
  -EncryptedFile 'D:\BrandistaPrivateDev\invitations\<id>-<uuid>.invitation.enc' `
  -ApiBase 'http://localhost:8080'
```

The helper prompts for the delivery key and **recipient's customer JWT** with masked input. It never prints/copies the token or puts it on the command line; only safe IDs and recipient email are displayed. It rejects non-local URLs and sanitizes failure diagnostics. Do not use it with real/demo accounts. Ordinary .NET/Java strings cannot be reliably erased; protect process memory and the workstation.
6. As SUPER_ADMIN, GET onboarding, verify `activationEligible=true`, then POST activate. Verify merchant access for that brand, customer access still works, and other brands remain denied. Acceptance alone does not activate.
7. For expired/lost pending delivery, reissue and deliver the replacement privately. For compromise, revoke the pending invitation; for an already accepted invitation revoke the membership using the existing controlled PR3 operation. Never edit audit rows. Re-invite explicitly after remediation. Disabled identities must be restored through authorized account controls, not by invitation acceptance.

## Migration, audit and retention

V13 adds `brand_admin_invitations`, `brand_invitation_accept_limits` and nullable `target_invitation_id` / `target_application_id` audit columns. Foreign keys trace application, brand, creator, accepted user, membership and reissue lineage. The unique SHA-256 token hash index enforces one stored digest per token. CSPRNG generates 256-bit tokens; only hashes persist in relational storage. Existing V1–V12 migrations/checksums and business rows remain unchanged; there is no automatic privilege, invitation, account or brand activation.

Audit uses PR2's insert-only application API and closed operation/reason enums. Approval, create/reissue/revoke/accept, membership grant and brand activation successes commit with their changes; rejected policy/token/rate-limit operations are recorded without secrets. IDs link the application/invitation/membership, with UTC timestamps and authenticated actor IDs. Audit failure fails closed and rolls back mutations. Existing authorization-denied auditing covers super-admin routes. The deployment database administrator remains trusted; retain PR2 least-privilege database/backup and immutable export guidance in [the bootstrap operations document](PR2-SUPER-ADMIN-OPERATIONS.md).

Retain invitation lineage and security audit records according to the approved security/privacy policy; this PR performs no automated deletion. Contact emails are personal data with restricted super-admin visibility. Expired/revoked tokens cannot authenticate even while retained. Clean development encrypted files after acceptance/expiry/revocation and according to that policy; keep encrypted backup keys separate and remove them when no retained payload requires them. Rotate a compromised development key by stopping delivery, revoking outstanding invitations, replacing the key, then explicitly reissuing. Quota rows can be reset/expired by a controlled database maintenance job after their windows, but are bounded by account count. Keep audit retention and DB-level tamper resistance outside customer/admin HTTP APIs.

## Verification

`./mvnw.cmd --batch-mode --no-transfer-progress verify` runs the full backend suite including customer registration/login, addresses, cart, COD checkout/order, bootstrap/audit and tenant regression tests. New tests cover all invitation states, wrong email, new/existing accounts, disabled accounts, stale grants/JWTs, concurrent issue/approval/acceptance, global activation bypass, revocation-version fencing, delivery/audit rollback, quotas, encryption and secret redaction.

`./scripts/verify-admin-pr4-mysql.ps1` starts only a new disposable `mysql:8.4.11-oraclelinux9` container on an ephemeral localhost port. It snapshots synthetic populated V12 commerce/security/application/membership rows, applies V13 and verifies preservation, then runs invitation integration tests against an empty isolated MySQL schema using Flyway V1–V13. It stops/removes only its own container and restores temporary environment variables. Docker must be available; it never uses the configured live/demo datasource.
