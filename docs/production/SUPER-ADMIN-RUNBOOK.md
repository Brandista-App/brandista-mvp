# Production owner setup: one-command Windows workflow

Revision: 2026-10-29. This supersedes PR #35's owner launcher. See [implementation findings](SUPER-ADMIN-FINDINGS.md), [verification record](SUPER-ADMIN-VERIFICATION.md), and the existing [audited recovery and custodian operations](../admin/PR2-SUPER-ADMIN-OPERATIONS.md).

## Run after review and merge

From a normal interactive Windows Terminal or PowerShell console, run this single line:

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "D:\brandista workplace\super-admin-provisioning\scripts\production-owner.ps1"
```

The execution-policy override applies only to that new process. No machine/user policy is changed. PowerShell 7 can run the same script using `pwsh.exe` in place of `powershell.exe`. IDE output panes, ISE and redirected input are not supported: they fail clearly before requesting passwords. Do not pipe or redirect this command.

Unavoidable prerequisites: Java **21 JDK** (including javac) and Git on PATH; the existing clean Brandista backend checkout at the stated path; GitHub/Maven network access for the reviewed source and first build; the approved Aiven CA-only PKCS12 file at `%USERPROFILE%\brandista-aiven-truststore.p12`; its password; the exact approved Aiven service hostname/port; and an authorized least-privilege database identity. This command does not obtain credentials, generate a default Super Admin password or guess service settings.

The launcher checks Java and Git, verifies the repository origin, fetches reviewed `origin/main`, and updates an ancestor checkout with a safe fast-forward. A squash/rebase-merged checkout with an exactly matching reviewed tree is also accepted. Unreviewed/diverged content and tracked edits are rejected without overwriting files. After updating itself, it relaunches the current reviewed script. It builds with the existing Maven wrapper's full `verify` before secrets are requested. A cached JAR is reused only when its SHA-256 and recorded reviewed commit match; a missing or altered cache rebuilds. Build output remains under ignored `target/owner-setup-build.log`. Clean terminal environment is required; inherited application/DB configuration is excluded temporarily and restored afterward.

An approved operator may explicitly select a reviewed release SHA using `-ReviewedCommit <full-SHA>`; it must equal the clean checkout HEAD. This option does not skip build verification, TLS, database checks, confirmation or authorization. CI uses it to exercise the PR commit before merge. Normal production usage requires no such option.

## Expected prompts and behavior

1. The reviewed Git revision and build/cache result appear. No database connection has happened.
2. Enter the approved Aiven **DNS hostname** and **port**. These are non-secret prompts. The database is fixed to `brandista`; credentials or arbitrary JDBC query parameters are never accepted in these fields.
3. At `Truststore password (hidden):`, enter the existing PKCS12 password. Java unlocks the store, requires valid CA certificate entries and rejects empty/wrong/expired stores before connecting.
4. Confirm `CONNECT brandista` to authorize read-only verification of the displayed target. Cancellation makes no database connection.
5. At `Aiven database username (hidden):` and `Aiven database password (hidden):`, enter the approved credentials. These are read directly inside Java's real Console, with echo disabled. Passwords never go into PowerShell variables, argv or environment JSON.
6. Java connects with Connector/J `sslMode=VERIFY_IDENTITY`, the explicit PKCS12 CA store and no system-store fallback. It checks the actual `SELECT DATABASE()` value is `brandista`, verifies an encrypted session, checks successful Flyway V1-V14 history, and reads the owner projection without returning password hashes.
7. If `ACCOUNT_EXISTS` and ready, the command succeeds without asking for an account password or changing any user/grant/audit record. If the account exists but is not enabled/granted/BCrypt, it reports `ACCOUNT_NOT_READY` and stops unchanged. No silent promotion, password reset or enablement is offered.
8. If `ACCOUNT_ABSENT`, the tool checks the permanent bootstrap marker and historical grants. Prior initialization blocks initial creation; it never resets that marker or silently chooses recovery.
9. If eligible, explicitly type `CREATE uzair@brandistaapp.com IN brandista`. Enter and confirm the chosen Super Admin password at the hidden Java prompts. The existing policy is at least 12 Unicode code points and at most 72 UTF-8 bytes; use a unique password-manager-generated password.
10. The existing offline audited service performs `INITIAL_CREATE` once. It creates an enabled CUSTOMER base identity with an explicit active SUPER_ADMIN grant, using the existing BCrypt encoder. The committed state is read again and matched to the returned numeric ID.

Expected creation success:

```text
SUCCESS: uzair@brandistaapp.com was created and verified in brandista; ENABLED, BCrypt format verified, SUPER_ADMIN grant active. User ID: <id>.
Owner setup completed successfully. Use the private password to sign in at https://admin.brandistaapp.com/login.
```

Expected existing-account success includes `No account changes were made.` Existing-account execution is safely repeatable. It does not ask for or test the existing login password; portal login remains the owner's private verification step.

To perform only the same read-only verification, append `-VerifyOnly` to the one-line command. Absence returns a distinct Java status 10 and a nonzero launcher result; type no subsequent `VERIFY` command in PowerShell. The tool no longer depends on a menu being reached after a database failure.

## Safe diagnostics and exit codes

| Java code/category | Meaning |
| --- | --- |
| 0 / SUCCESS | Existing or newly created owner verified ready in the actual connected database |
| 10 / ACCOUNT_ABSENT | Read-only verification completed; owner absent |
| 11 / ACCOUNT_NOT_READY | Existing owner retained unchanged; separately approved recovery needed |
| 12 / CANCELLED | Operator declined connection or creation |
| 20 / CONSOLE_UNAVAILABLE | No real terminal; no password requested or database connection |
| 21 / INPUT_INVALID | Invalid options, password policy or confirmation; no account operation |
| 22 / TRUSTSTORE_INVALID | Missing, unreadable, empty, wrong-password or invalid/expired CA PKCS12 store |
| 23 / TLS_FAILED | TLS chain, hostname or encrypted-session verification failure; no bypass offered |
| 24 / DATABASE_CREDENTIALS | Database username/password rejected |
| 25 / CONNECTION_FAILED | DNS, timeout, refused connection or unavailable database |
| 26 / MIGRATIONS_MISSING | Required successful Flyway history/table/column missing; no migration attempted |
| 27 / DATABASE_MISMATCH | Actual database is not brandista, or brandista does not exist |
| 28 / DATABASE_PERMISSION | Approved identity lacks privileges for the operation |
| 29 / OPERATOR_STARTUP | Reviewed offline application could not initialize |
| 30 / INITIALIZATION_BLOCKED or PROVISIONING_REJECTED | Prior bootstrap/history or service safety check prevents creation |
| 31 / STATE_UNCERTAIN | An operation was attempted but final state could not be verified; never retry automatically |

The PowerShell launcher preserves failure as a nonzero process result (normally 1 for its terminating exception) and includes the Java code/category. Failures before an operation print `NO_ACCOUNT_CHANGES`. After an uncertain mutation, use the same one-line command with `-VerifyOnly` first. Driver exception messages, SQL parameter values, passwords, hashes, tokens and complete configuration are never shown. Diagnostics classify exception types and numeric SQL codes only. There is no automatic mutation retry.

## Database permissions and schema

Use a short-lived operator identity, not a broad Aiven DBA or changed Render credential. Read-only mode needs SELECT on users, user_platform_grants, security_provisioning_state and flyway_schema_history. Initial creation additionally needs INSERT on users and user_platform_grants, UPDATE on security_provisioning_state and INSERT on security_audit_events. Two pooled connections are available for independent failure auditing. Do not grant DELETE, DROP, ALTER, CREATE, global grants, or audit UPDATE/DELETE. Apply normal existing additive migrations using a separately approved deployment/migration identity **before** this tool. This console disables Flyway and SQL initialization.

The operator verifies `brandista` from the live connection itself. This development task does not establish the production account state or production DB name by making a connection: production access still requires explicit owner approval. No local fixture is treated as production evidence.

Independent read-only SQL (approved observer, verified TLS client, secure password prompt):

```sql
SELECT u.id,u.email,u.role AS base_role,u.status,
       (CHAR_LENGTH(u.password_hash)=60 AND
        (u.password_hash LIKE '$2a$%' OR u.password_hash LIKE '$2b$%' OR u.password_hash LIKE '$2y$%')) AS bcrypt_format,
       EXISTS (SELECT 1 FROM user_platform_grants g WHERE g.user_id=u.id
               AND g.grant_type='SUPER_ADMIN' AND g.revoked_at IS NULL) AS active_super_admin
FROM users u WHERE u.email='uzair@brandistaapp.com';
```

## Login, recovery and deployment boundaries

After success, privately sign in at `https://admin.brandistaapp.com/login` in admin mode. The login API returns the independent base role (normally CUSTOMER); the portal verifies SUPER_ADMIN using `/api/v1/super-admin/identity`. Check matching user ID, enabled state and grant. Do not copy JWTs, authorization headers, passwords or HAR captures into tickets. Negative-password/ordinary-account checks and persistence across a Render restart/redeploy require separately approved live actions.

The one-command setup never resets passwords or grants on existing accounts. Approved administrator-assisted recovery remains available through the existing [PR2 offline procedure](../admin/PR2-SUPER-ADMIN-OPERATIONS.md), with verified ID/email and separately authorized RESET_PASSWORD/recovery gates. No operational email forgot-password flow is claimed. A password reset alone does not revoke existing JWTs; suspected compromise requires approved custodian replacement/revocation while preserving the last active custodian.

This PR adds no HTTP provisioning endpoint, schema migration, startup seeder, authentication change, Render setting change or deployment. Merge/deploy requires separate approval because main may auto-deploy. Before mutation, rollback means stopping/reverting the operator files; production data remains untouched. After mutation, keep the compatible persistent account and use approved audited recovery/custodian rotation if needed. Never reset bootstrap state, delete accounts/grants/audit records, restore stale hashes or overwrite the production database to undo provisioning.
