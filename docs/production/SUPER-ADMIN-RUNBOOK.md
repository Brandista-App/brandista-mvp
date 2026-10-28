# Production Super Admin: Windows operator runbook

Prepared 2026-10-28. See [inspection findings](SUPER-ADMIN-FINDINGS.md) and [existing PR2 operations](../admin/PR2-SUPER-ADMIN-OPERATIONS.md).

## Current delivery status

The existing offline service is reused. The new console is fixed to `uzair@brandistaapp.com`, permits VERIFY / INITIAL_CREATE / INITIAL_GRANT / RESET_PASSWORD only, and verifies the committed enabled account, BCrypt format and active grant after success. Repeated create fails safely. There is no automatic recovery retry or password/role overwrite. VERIFY performs SELECTs only. No public endpoint, schema change, automatic seeder or production web configuration change is introduced.

Read-only live checks: Render reports the existing base revision b5f6d5d as live; API /readyz and admin /login both returned HTTP 200. Current Render health-check configuration is /livez. No deployment or setting change was performed. Production provisioning, owner account existence, direct workstation Aiven connection, authenticated portal login and owner persistence across restarts remain **NOT VERIFIED**. Obtain explicit owner approval naming the target database and operation before either mutation switch is used. A typed confirmation is a local execution safeguard; it is not a substitute for approval.

## Prerequisites and database access

Use a trusted Windows Terminal / PowerShell session with Java 21, Maven wrapper access and the reviewed jar. Disable transcripts, recording and tracing. Do not use IDE consoles, pipe passwords, run under screen sharing, or paste secrets into chat. Java reads the account password directly with `Console.readPassword`; database/truststore passwords use `Read-Host -AsSecureString`. Password-manager generate a unique password meeting the existing 12-code-point minimum / 72-UTF-8-byte maximum.

In Aiven's approved account, independently obtain the exact **MySQL service DNS hostname, service port, CA certificate and operator identity**, not a local Docker connection. The launcher accepts only `*.aivencloud.com` DNS names and fixes database `brandista`; if the approved service uses a different domain, review the validation before use. Never guess a hostname/port from examples. Network allowlists must permit this trusted workstation. Existing Render docs use runtime `brandista_app`; request a separate short-lived operator account rather than changing its credentials.

Have the DBA apply/review existing additive migrations through V14 using the normal migration identity first. This console disables Flyway and checks required successful schema-history entries. It cannot apply pending migrations. Do not run generic bootstrap against production without this safeguard. No DDL is needed for this change.

DBA access design (do not execute GRANTs without authorization): observer needs SELECT on `users`, `user_platform_grants`, `flyway_schema_history`. Initial-create operator needs SELECT/INSERT on `users` and `user_platform_grants`; SELECT/UPDATE on singleton `security_provisioning_state`; INSERT on `security_audit_events`; SELECT on schema history. Existing grant/recovery also needs UPDATE on `user_platform_grants`; RESET_PASSWORD needs UPDATE on `users.password_hash` and `users.updated_at`. Reduce column privileges where practical. No DELETE, DROP, ALTER, CREATE, global grant, or audit UPDATE/DELETE is needed. Grant schema-history SELECT explicitly. Restrict source IP, expire/revoke operator access afterward, and keep audit exports protected. Application runtime rights are separate.

The observer can see credential columns at SQL privilege level unless a DBA supplies a column-specific grant or view; the supplied projection returns only a format boolean. Never SELECT the hash itself.

## Build and CA trust preparation

Run from the reviewed backend checkout, **before entering production secrets**:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd --batch-mode --no-transfer-progress verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Get-FileHash .\target\brandista-0.0.1-SNAPSHOT.jar -Algorithm SHA256
```

Save the Aiven CA outside Git in an access-restricted operator folder. Verify its SHA-256 fingerprint through the approved Aiven service/account. Create a new truststore at a new path with only that approved CA; keytool prompts for the store password and certificate trust confirmation:

```powershell
$ownerCa = Read-Host 'Absolute path to approved Aiven CA PEM'
$ownerTrust = Read-Host 'Absolute path for a NEW operator Aiven PKCS12 file (.p12)'
if (Test-Path -LiteralPath $ownerTrust) { throw 'Use a new truststore path' }
keytool -printcert -file $ownerCa
# Compare the fingerprint independently before importing.
keytool -importcert -alias brandista-aiven-ca -file $ownerCa -keystore $ownerTrust -storetype PKCS12
if ($LASTEXITCODE -ne 0) { throw 'CA import failed' }
keytool -list -keystore $ownerTrust -storetype PKCS12
if ($LASTEXITCODE -ne 0) { throw 'Truststore verification failed' }
```

This workstation launcher uses Connector/J's per-connection truststore, `sslMode=VERIFY_IDENTITY` and `fallbackToSystemTrustStore=false`. The certificate and DNS name must verify; no TLS bypass is offered. This is separate from Render's merged JVM truststore and must not be copied into the Render configuration. See [Connector/J server authentication documentation](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html).

## Read-only preflight and initial creation

Use a clean dedicated terminal without Java option injectors or custom Spring config locations. Enter host and port as non-secret values:

```powershell
$ownerDbHost = Read-Host 'Approved Aiven MySQL DNS hostname'
$ownerDbPort = [int](Read-Host 'Approved Aiven MySQL port')
.\scripts\production-owner.ps1 -DatabaseHost $ownerDbHost -DatabasePort $ownerDbPort -TrustStore $ownerTrust
```

Supply the observer username and masked database/store passwords. At Java's mode prompt enter `VERIFY`. An absent account prints ABSENT and exits nonzero; an existing account prints only ID, email, base role, status and booleans. Any connection/schema error stops without an operation. This is the step that determines whether the owner exists in actual Aiven; local test results cannot answer it.

After the owner's explicit approval for **INITIAL_CREATE on this Aiven database**, use the short-lived operator identity:

```powershell
.\scripts\production-owner.ps1 -DatabaseHost $ownerDbHost -DatabasePort $ownerDbPort -TrustStore $ownerTrust -AuthorizeMutation
```

Select `INITIAL_CREATE`; type `APPROVED INITIAL_CREATE uzair@brandistaapp.com` when prompted. Enter and confirm the privately chosen account password. Success requires both audited transaction completion and a subsequent SELECT showing committed enabled state, BCrypt format and SUPER_ADMIN grant. Exit 0 means verified persistence; exit 1 means absent/unready VERIFY or rejected service result; exit 2 means abort/configuration/verification failure. PowerShell throws on nonzero. If failure follows a mutation, inspect using VERIFY before any retry because the transaction might have committed.

If the email already exists, **stop**. Never change its password or base role automatically. If independently verified ownership and the numeric ID are explicitly approved, INITIAL_GRANT preserves its existing password and base role; use the same launcher with AuthorizeMutation, select INITIAL_GRANT and confirm that operation. A disabled account is rejected. ALREADY_INITIALIZED means this database has prior bootstrap/grant history: do not clear the singleton or use recovery to bypass it. Follow the separate reviewed PR2 recovery/grant procedure only with approval for that exact action. The fixed-owner console intentionally cannot create extra custodians.

Secrets exist transiently in the trusted process environment through `SPRING_APPLICATION_JSON`; they are not argv or files. The launcher restores the prior environment and disposes secure buffers in finally. PowerShell/Java immutable strings cannot guarantee complete memory erasure, so use a trusted host and short-lived credentials. Do not run Maven/tests while production secret environment values are loaded.

## Independent read-only SQL verification

Use an approved MySQL client with TLS VERIFY_IDENTITY, the verified Aiven CA, and an observer identity. Enter its password at the client's secure prompt. Never pass `-pPASSWORD`. Run:

```sql
SELECT u.id, u.email, u.role AS base_role, u.status,
       (CHAR_LENGTH(u.password_hash)=60 AND
        (u.password_hash LIKE '$2a$%' OR u.password_hash LIKE '$2b$%' OR
         u.password_hash LIKE '$2y$%')) AS bcrypt_format,
       EXISTS (SELECT 1 FROM user_platform_grants g
               WHERE g.user_id=u.id AND g.grant_type='SUPER_ADMIN'
                 AND g.revoked_at IS NULL) AS active_super_admin
FROM users u WHERE u.email='uzair@brandistaapp.com';
```

Expect exactly one row, ENABLED, bcrypt_format=1, active_super_admin=1. Base role CUSTOMER is expected for new creation. Format alone does not prove that the chosen password matches; actual login supplies that evidence. Review the matching INITIAL_CREATE / INITIAL_GRANT / RESET_PASSWORD audit outcome by target numeric ID without dumping arbitrary database rows.

## Safe actual production verification

1. Record approved commit/jar digest and backend deployed revision. Check `https://api.brandistaapp.com/readyz` returns 200 (production profile). Run VERIFY against Aiven; retain only redacted status booleans and numeric ID.
2. In a private browser window open `https://admin.brandistaapp.com/login`, choose administrator mode, and enter `uzair@brandistaapp.com` plus the private password. Do not paste credentials into tools/chat, export HAR, copy response bodies or capture DevTools screenshots containing tokens.
3. Confirm POST `https://api.brandistaapp.com/api/v1/auth/login` is 200. Its account ID/email must match the projection; account.role may correctly be CUSTOMER. The subsequent GET `/api/v1/super-admin/identity` must be 200 with the same userId and platformGrants containing SUPER_ADMIN. This probe is the portal's authoritative capability check. The portal must open the admin workspace. Record statuses only, never accessToken or Authorization headers.
4. Sign out; make one deliberately incorrect-password login attempt and confirm rejection (401). Avoid repeated retries. Sign in again with the correct password.
5. With separately approved ordinary customer, legacy ADMIN-without-grant and brand-member test identities, confirm `/api/v1/super-admin/identity` denies access (403 with valid authentication). Do not grant these accounts privileges or create production fixtures for this check. Isolated tests cover this when no approved live identities are available; label the live check pending.
6. After a **separately approved** normal Render restart and redeploy, rerun observer VERIFY and repeat portal login. Match the same account ID, email, enabled state and grant; no startup seeder should run. Never trigger a deploy/restart merely to complete this checklist without approval.

## Password recovery until email reset is configured

There is no operational email forgot-password flow in this revision. Do not offer a public reset-token request, claim delivery, log tokens, or repurpose invitation emails. Token expiry/single-use and request-rate-limit tests are not applicable to this assisted-only workflow. A future email-reset implementation requires a reviewed provider, hashed random single-use expiring tokens, transactional consumption, enumeration-safe responses, rate limits and security audit tests before claiming availability.

For assisted recovery, the owner contacts a trusted operator through the established private support channel. Verify account ownership out of band and obtain explicit approval for RESET_PASSWORD on the verified numeric ID/database. Use observer VERIFY first. The enabled account must already have an active SUPER_ADMIN grant. Then:

```powershell
.\scripts\production-owner.ps1 -DatabaseHost $ownerDbHost -DatabasePort $ownerDbPort -TrustStore $ownerTrust -AuthorizeMutation -AuthorizePasswordRecovery
```

Select `RESET_PASSWORD`, enter the independently verified user ID, and type `APPROVED RESET_PASSWORD uzair@brandistaapp.com`. Have the owner enter/confirm a new unique password privately at Java's masked prompts on the trusted terminal. No password is sent to the operator through tickets or email. The audited service changes only password hash and updated_at; account ID, email, role, status and grant persist. Verify new login succeeds and old password fails; retain statuses only. Remove operator access afterward.

Password reset alone **does not invalidate existing JWTs**. For suspected compromise, follow PR2 approved custodian replacement and revoke/disable workflow, preserving at least one verified active custodian. Do not weaken last-admin protection or reset the bootstrap marker. If the only custodian is disabled or compromised, escalate to the separately approved PR2 recovery process; this fixed-owner workflow fails closed.

## Deployment checklist and rollback

- Review findings, source, tests, TLS validation and full PR before merge. No changes to Render settings or new migrations are required. Review enabled auto-deploy before merging; this task does not authorize merge/deploy.
- Verify current deployed V10-V14 history, database backup/restore readiness, least-privilege identities, CA fingerprint, workstation access and owner approval.
- Build/test without production secrets, record jar digest, run observer preflight, then obtain exact mutation approval. Provision once; remove operator credentials afterward.
- Complete the production checklist above and record PASS / FAIL / PENDING per item. Production completion requires explicit approval and successful live evidence, including approved restart/redeploy.
- Before mutation, rollback is to stop and discard/revert these operator-only files; production data is untouched. After mutation, reverting the jar leaves the existing persistent account intact and usable by the prior compatible backend. Never restore a stale whole-database backup or delete users/grants/audit rows to undo provisioning.
- For unintended grants/password compromise use approved audited PR2 recovery/revocation/custodian rotation; first verify another custodian. Recover passwords through RESET_PASSWORD, not hash restoration. No destructive SQL rollback is supplied.
