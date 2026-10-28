# Owner setup follow-up: packaged Windows acceptance

Date: 2026-10-29. Base: merged PR #35 / 80439d9. Production connections, account writes, merge and deployment are not authorized or performed.

## Diagnosis grounded in reproduction

PR #35 checks `System.console()` first. An unavailable console produces its own message and exits before Spring; the reported generic `Owner command failed` therefore comes from a later exception before the menu. That handler discarded the exception category and misleadingly warned of a possible mutation even during read-only startup. The menu was after connection/schema/account inspection, so typing VERIFY after exit could never work.

A packaged PR #35 JAR was exercised through a real Windows PowerShell 5.1 pseudoconsole against a fresh loopback TLS MySQL fixture. With a valid keytool-imported CA store and fixture schema, it reached the operation menu. A CA-less/unusable PKCS12 truststore reproduced the same generic pre-menu failure; an isolated probe reported only closed exception class/SQL code information, with certificate/trust-anchor exceptions. This demonstrates the missing diagnostic/preflight path, not proof that the production operator's specific store or password was wrong. The actual production exception cannot be recovered from the original generic text, and no production connection was attempted to guess it.

## Changes

- Replace the owner menu dependency with read-only-first setup. Confirm actual `SELECT DATABASE()=brandista`, encrypted session, required Flyway history and account state before requesting any creation approval.
- Unlock and validate CA-only PKCS12 in Java before database access. All DB credentials/store/account passwords use real masked `Console.readPassword`; no plaintext pipes, argv or PowerShell environment JSON.
- Bind driver TLS properties directly with their exact Connector/J names; retain VERIFY_IDENTITY and disable system-store fallback. No authentication/BCrypt/grant/audit/business-service replacement.
- Closed diagnostics distinguish console/input/store/TLS/credentials/network/schema/database/permission/startup/service/uncertain-state failures. Pre-operation errors say NO_ACCOUNT_CHANGES. Never retry a mutation automatically.
- One command checks Java 21 JDK/Git, validates the repository and reviewed main, safely fast-forwards ancestor code, rebuilds with full Maven verify when the reviewed-commit/JAR digest cache is absent or changed, and then launches Java in the same console.
- Windows PowerShell 5.1 native stderr behavior is handled explicitly for prerequisite/build commands; native failures are checked rather than suppressed. The launcher does not use PS7-only .NET APIs.
- Existing owners are never reset, promoted, enabled or otherwise modified by setup. Existing audited INITIAL_CREATE, duplicate/race protection and permanent bootstrap state remain authoritative. Recovery stays separately approved through PR2.

## Test evidence

Focused Java options/truststore/schema/diagnostic tests: PASS in the initial build. Updated launcher contract tests: PASS on Windows PowerShell 5.1 and PowerShell 7; Actions exit/failure propagation regression: PASS. The original mock-only TLS assertions have been replaced by real packaged-driver TLS and hostname-failure acceptance, while launcher contracts retain prerequisite, artifact-integrity, safe-argv, cleanup, failure and injection coverage.

Full packaged native Windows acceptance and hosted CI results: PENDING final runs. The test starts a new native MySQL datadir, on a newly selected loopback port, with generated two-day CA/server certificates and fake credentials. It never accepts an external DB target, touches existing MySQL services, or uploads raw terminal output. It tests both shells, actual Console masking, default truststore path, reviewed build/cache, actual Flyway migrations, read-only absence, cancellation, account creation, repeated execution, incorrect credentials/store password, hostname verification, missing migration, existing disabled account, refused connection and missing console. Cleanup validates its dedicated nonce directory is inside target before removing it.

See [the one-line command, prerequisites, exact prompts and final success text](SUPER-ADMIN-RUNBOOK.md). Production account state and production login remain unverified until the owner explicitly authorizes and performs that workflow. Successful isolated tests are not production-account evidence.
