# PR2: offline super-admin provisioning and security audit

## Scope and prerequisites

PR1 (#20) is merged. V10's explicit platform grants and per-request account/grant
lookups remain authoritative; a legacy `ADMIN` is not a `SUPER_ADMIN`. The account's
customer/legacy role is independent of its platform grant. Public registration
cannot submit privileges. This PR adds no provisioning HTTP endpoint or frontend.

Only a trusted operator with approved database access may run the separate command.
**Obtain the owner's explicit approval for the specific live/demo database and
account before executing provisioning there.** Implementation and verification do
not imply that approval. Use an interactive Windows Terminal/PowerShell with Java
21. Do not use an IDE console, pipes, transcripts, terminal recording, shell tracing,
SQL parameter logging, or debug/trace logging for provisioning.

Build the reviewed revision before configuring database secrets:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd --batch-mode --no-transfer-progress verify
if ($LASTEXITCODE -ne 0) { throw 'Build verification failed' }
```

Use an approved deployment/migration identity to apply additive Flyway V11 before
running the web application. V1-V10 remain unchanged. The command also uses Flyway;
with a restricted runtime identity, all migrations must already be current. V11
creates a permanent singleton state row and an empty audit table, without changing
accounts or grants. Never reset/delete the state row to repeat bootstrap.

## Initial provisioning in PowerShell

Run from the repository root. First independently verify the database host, database
name, intended account identity and approved artifact. Enter configuration at the
prompts; do not paste secrets into commands, `.env` files, source files, or tickets.
The database password is transported in the child process environment, never argv.
The new account password is read directly by Java's masked `Console.readPassword`,
not by PowerShell or a shell argument. Environment access by a privileged local
operator remains within the trusted host boundary.

```powershell
$env:SPRING_DATASOURCE_URL = Read-Host 'Approved JDBC URL (no embedded credentials)'
$env:SPRING_DATASOURCE_USERNAME = Read-Host 'Approved operator database username'
$operatorDbSecret = Read-Host 'Database password' -AsSecureString
$operatorDbPointer = [IntPtr]::Zero
try {
    $operatorDbPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($operatorDbSecret)
    $env:SPRING_DATASOURCE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($operatorDbPointer)
    $env:BRANDISTA_SECURITY_PROVISIONING_ENABLED = 'true'
    $env:BRANDISTA_SECURITY_PROVISIONING_RECOVERY_ENABLED = 'false'
    java '-Dloader.main=com.brandista.operator.BootstrapMain' `
        -cp .\target\brandista-0.0.1-SNAPSHOT.jar `
        org.springframework.boot.loader.launch.PropertiesLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Provisioning rejected or failed; inspect audit outcome securely' }
} finally {
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BRANDISTA_SECURITY_PROVISIONING_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:BRANDISTA_SECURITY_PROVISIONING_RECOVERY_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
    Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
    if ($operatorDbPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($operatorDbPointer)
    }
    $operatorDbSecret.Dispose()
}
```

The default packaged entry point remains the web app. `PropertiesLauncher` selects
the separate offline entry point, which has no controllers, HTTP listener, or JWT
secret requirement. Do not enable provisioning flags on the deployed web process.
Both flags are false when absent; ordinary startup never provisions accounts.

At the Java prompts select exactly one initial mode:

| Mode | Effect |
| --- | --- |
| `INITIAL_CREATE` | Create a new enabled CUSTOMER account and explicit SUPER_ADMIN grant atomically. Reject any existing normalized email; never change its password or silently promote it. |
| `INITIAL_GRANT` | Grant an existing enabled account after independently verifying its numeric user ID and normalized email. Preserve password and legacy/customer role; no account creation or password entry. |

Enter the email, verified existing user ID when prompted, and `PROCEED`. For a new
account enter and confirm a unique password from the approved password manager
(at least 12 characters, at most 72 UTF-8 bytes). No credentials are supplied by the
application. Successful output contains only outcome/reason and numeric target ID.
Exit code 0 means success, 1 means rejection/storage failure with a result, 2 means
an aborted invocation or unavailable terminal/configuration/audit storage.

Confirm the success audit event using a read-only security observer identity. Log
in through the normal authentication endpoint and verify `/api/v1/super-admin/identity`.
Do not paste JWTs into shell commands or tickets. Remove the one-time operator
database access when finished. Repeat or concurrent initial attempts are rejected;
existing historical grants also prevent bootstrap even if the marker was never set.
Prefer creating a dedicated new identity when the ownership or session history of
an existing account is uncertain. As in PR1, a newly granted privilege applies on
the next request to all still-valid JWTs for that account; grant issuance does not
invalidate its existing sessions. Verify account control before choosing a grant
mode. Do not grant an account suspected of compromise; recover with a new custodian.

## Controlled recovery and custodian rotation

Recovery is an explicit break-glass operator action, not a public account recovery
API. Obtain approval for the target and operation, verify identity out of band, and
use the same procedure with **both** flags true only for that process:

```powershell
$env:BRANDISTA_SECURITY_PROVISIONING_RECOVERY_ENABLED = 'true'
```

Place that assignment inside the procedure's `try` block, replacing its `false`
assignment. Keep the same `finally` cleanup. Recovery modes:

| Mode | Effect |
| --- | --- |
| `RECOVER_CREATE` | Create a separately approved new account plus grant. Useful if every custodian is unavailable/disabled. Existing email is rejected. |
| `RECOVER_GRANT` | Grant/reactivate a grant for an independently verified enabled existing account using ID + email; preserve credentials and role. Disabled accounts are rejected. |
| `RESET_PASSWORD` | Reset the password of an enabled, currently granted super-admin only; never grant privileges or reset an arbitrary customer account. |
| `REVOKE` | Revoke an explicit grant; preserve the account and customer behavior. |
| `DISABLE` | Disable a verified enabled account with a current or historical super-admin grant. |

For rotation, first add and verify a replacement custodian, then revoke/disable the
old one. Keep at least two separately controlled custodians operational. Revoking
or disabling the last enabled grantee is rejected, including concurrent attempts.
Ordinary JPA updates/removals have the same invariant; repository bulk deletes
are blocked because they bypass entity callbacks. No account deletion command is
provided. Direct DBA SQL/native JPQL is not a supported application mutation path.

Password reset does not invalidate previously issued JWTs by itself. For suspected
token theft, revoke the old account's platform grant and, when appropriate, disable
the account after installing a replacement custodian. Current account/grant lookup
then rejects privileged access with old tokens immediately. A revoked enabled
customer retains customer access. There is no JWT/authority cache.
Revocation does not remove an independent legacy ADMIN role's catalogue access;
disable that account as well when all authenticated access must be removed. The
disable command accepts historically granted accounts even after grant revocation.

## Audit security and retention

`security_audit_events` stores numeric ID, actor (`user:<id>` or trusted
`process:<OS-user>`), closed operation name, numeric target user ID when known,
UTC timestamp, outcome, and a closed reason code as safe metadata. No arbitrary
payload, email, password/hash, raw JWT, headers, query, request body or exception
message is recorded. Rejected initial attempts survive independently of success;
successful mutations and their audit rows commit together. Audit insert failures
roll back mutations. Rejected last-admin JPA writes use a separate transaction so
their audit rows survive rollback. Allow at least two pooled connections for this.

Audited operations include provisioning/recovery, password reset, grant lifecycle,
account security field changes/removals, and successful/denied super-admin access.
An operator cancellation, invalid console mode or password confirmation mismatch
before service invocation performs no database mutation/audit attempt. A database
outage can prevent durable rejected-attempt recording; the command fails closed
and prints a generic failure. Investigate using protected operational telemetry.

The application provides an insert-only audit writer, no mutable audit entity,
repository, or public audit endpoints. Existing ADMIN and customers cannot edit or
delete events; even SUPER_ADMIN has no audit mutation route. This is tamper
resistance at the application authorization layer, not protection against a DBA
or compromised database credentials. Use separate identities for Flyway DDL,
runtime DML, read-only security observation, and DBA retention. On MySQL grant the
runtime identity INSERT/SELECT on `security_audit_events`, and explicitly withhold
UPDATE/DELETE/DROP/ALTER on it; avoid broad database-wide privileges that override
those restrictions. Restrict the operator identity and protect host access.

Export audit events regularly to restricted append-only/WORM storage; monitor
rejections, recovery use, export failures, unexpected gaps and privileged changes.
Set an owner-approved retention period under applicable requirements before
production rollout (recommended operational starting point: 365 days searchable,
with approved encrypted archive thereafter). Keep appending during exports; use
event ID and UTC timestamp as export checkpoints. Only a separate approved DBA
retention process may purge old, verified archived rows. Never cascade account
deletion into audit history. No automatic purge is introduced.

## Verification

Run `./mvnw.cmd --batch-mode --no-transfer-progress verify` for the complete H2
backend suite (including customer registration/login/cart/addresses/COD checkout).
The PR adds bootstrap/recovery/race/invariant/audit/old-token tests and a V10-to-V11
upgrade test. Run `./scripts/verify-admin-pr2-mysql.ps1` (Java 21 and Docker required)
for a new disposable MySQL 8.4.11 container, localhost-only random port, synthetic
data, real V10-to-V11 preservation, and the operator integration suite. It removes
its container afterward and never uses existing volumes or a deployment database.
Neither procedure provisions a
live/demo account. No admin frontend, invitations or CMS behavior is included.
