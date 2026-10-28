# Super Admin delivery and verification record

Date: 2026-10-28 (Asia/Karachi). Base: b5f6d5d. Production mutations/deployment: none.

## Authentication and implementation

See [pre-change findings](SUPER-ADMIN-FINDINGS.md). This change reuses BCrypt, normalized unique email, the existing user model, explicit SUPER_ADMIN grants, live DB authorization, bootstrap serialization, guarded recovery and transactionally audited operations. It adds an operator-only fixed-owner console, Windows Aiven TLS configuration and a credential-free persisted-state projection. The web application and existing authentication response remain compatible. No schema changes or new HTTP endpoints are introduced.

The [runbook](SUPER-ADMIN-RUNBOOK.md) contains complete PowerShell commands, CA import and fingerprint checks, least-privilege access, read-only SQL, duplicate handling, private password entry, actual production login checks, assisted recovery, deployment gates and rollback procedures.

## Exact changed files

- `.github/workflows/backend-tests.yml`: Windows wrapper contract job and script path triggers.
- `README.md`: links to owner operations.
- `docs/production/SUPER-ADMIN-FINDINGS.md`: implementation inspection and read-only live findings.
- `docs/production/SUPER-ADMIN-RUNBOOK.md`: production Windows workflow, verification, recovery and rollback.
- `docs/production/SUPER-ADMIN-VERIFICATION.md`: this delivery record.
- `scripts/production-owner.ps1`: fixed Aiven schema, VERIFY_IDENTITY and explicit operator/recovery flags; secure prompts and environment cleanup.
- `scripts/test-production-owner.ps1`: mocked wrapper contract checks without network access.
- `src/main/java/com/brandista/operator/OwnerAccountInspection.java`: read-only projection excluding credential material.
- `src/main/java/com/brandista/operator/OwnerProvisioningMain.java`: fixed-owner console, migration guard, explicit confirmation and committed-state verification.
- `src/test/java/com/brandista/operator/OwnerProvisioningConsoleTests.java`: isolated console creation, verification, cancellation, duplicate protection and reset tests.
- `src/test/java/com/brandista/brandista/privilege/OperatorProvisioningIntegrationTests.java`: password-reset login/old-password regression and persisted-state rejection checks.

## Automated validation

Results are finalized before PR creation below. Build output stays in ignored `target/`; it contains synthetic test data only and is not a production evidence source.

- Initial full `mvnw.cmd --batch-mode --no-transfer-progress verify`: PASS, 207 tests, 0 failures, 0 errors, 1 skipped. This preceded the two new console-level tests.
- Windows wrapper parser and `scripts/test-production-owner.ps1`: PASS, including TLS settings, fixed target, secret-free argv, default gates, recovery gates, Java failure propagation, environment restoration and Java-option injection rejection. Three Java invocations are mocked; these are not live TLS/database tests.
- Packaged noninteractive console rejection: PASS, exits 2 before database access when there is no real console.
- Console-level tests: PASS, 2 tests, 0 failures/errors. Creation, duplicate protection, committed-state projection, password reset and read-only/cancel behavior are exercised against isolated H2.
- Serial disposable MySQL 8.4.11 run: PASS, 19 tests, 0 failures/errors; V10-to-V11 existing account/grant preservation also passed. Its newly created labeled container was removed by the script.
- Final full `mvnw.cmd --batch-mode --no-transfer-progress verify`: PASS, 209 tests, 0 failures, 0 errors, 1 skipped; reviewed executable jar packaged successfully.

One initial MySQL attempt collided with a recompilation in the shared Maven target directory, causing missing-class failures. That attempt is not counted as a pass. The fixture container was removed; serial reruns determine the final result. Console tests also exposed H2's Flyway identifier-case behavior; the isolated H2 fixture now uses DATABASE_TO_LOWER=TRUE to match MySQL naming. Production errors remain generic and never print exception payloads.

Email password-reset token expiration/single-use/rate-limit tests are NOT APPLICABLE: no such endpoint or delivery mechanism is being introduced or claimed. The explicitly permitted fallback is assisted, audited password recovery. Password change does not revoke existing JWTs; compromise handling is documented through existing custodian rotation/revocation.

## Actual production evidence and outstanding checks

The owner confirmed Render workspace My Workspace for read-only checks. Render API reports service brandista-mvp at base revision b5f6d5d as live; both API /readyz and admin /login returned HTTP 200. This change did not trigger that deploy. Render metadata reports auto-deploy on main commits, so merging must be treated as a deployment approval decision.

| Required verification | Status |
| --- | --- |
| API readiness and login-page availability | PASS: unauthenticated HTTP 200 at observation time |
| Direct operator workstation Aiven TLS connection | PENDING observer VERIFY |
| Production email/account ID, enabled state, grant and BCrypt format | PENDING observer VERIFY / supplied SQL |
| Correct-password backend login and matching identity response | PENDING owner private login |
| Super Admin portal accepts the account and protected API authorizes it | PENDING owner private login |
| Wrong password rejected | PASS isolated regression; live check PENDING |
| Customer / legacy ADMIN / brand accounts denied Super Admin | PASS existing isolated authorization suites; live check PENDING approved identities |
| Owner survives actual Render restart and redeploy | PENDING separately approved restart/redeploy and repeated VERIFY/login |
| Assisted recovery preserves identity and authorizes new password | Automated results below; live recovery not executed |
| No unintended production data mutations by this work | No production database writes performed |

No production password, database credential, password hash or JWT is included in delivery output. We did not request production secret environment values. Account existence is not inferred from local tests or HTTP page availability.

## Approval boundary

Review the PR and runbook first. Production account creation, existing-account grant/password change, credential administration, merge/deploy and restart remain separately gated by explicit owner approval for the target and operation. Supply passwords only through the private local prompts; never through chat, PRs, scripts or shell arguments. No destructive database rollback is needed or provided.
