# Render / Aiven certificate trust repair

## Scope

The entrypoint imports the mounted Aiven CA **at runtime**, after Render mounts Secret Files. It preserves every certificate in Java 21's existing `cacerts` by copying that store into a new PKCS12 store and then adding the Aiven CA. The original Java truststore and image are not modified at runtime. No JDBC hostname, database, username, password, TLS mode, Spring behavior, migration or application health route is changed.

The application still runs as UID 10001 (`appuser`). Supplementary group 1000 permits reading Render's mounted Secret Files. `mktemp` creates a private directory under `/tmp` with mode 0700, and the truststore has mode 0600. A random per-start integrity password protects this public-certificate store; it is unrelated to database or application credentials. No real certificate, private key or application secret is committed or baked into the image.

CA setup rejects missing/unreadable/empty files, malformed PEM, non-CA certificates and certificates outside their validity period. Failed imports stop startup with a clear diagnostic before the application connects. A single PEM CA certificate is expected. Obtain the CA from the relevant Aiven service and verify its fingerprint through your approved Aiven account; no automatic CA download or trust bypass is introduced.

Local Docker use remains unchanged when neither `AIVEN_CA_CERT` nor `RENDER=true` is set. Render supplies `RENDER=true`, which selects `/etc/secrets/ca.pem`; explicitly setting `AIVEN_CA_CERT` makes the required path unambiguous. Existing application command arguments and standard Java environment options remain supported.

## Exact Render settings

For Docker Web Service **brandista-mvp**, after reviewing and merging the PR:

| Setting | Value / action |
| --- | --- |
| Deployment branch | `main` |
| Dockerfile path | `./Dockerfile` |
| Docker build context | Repository root (`.`) |
| Docker Command override | Leave empty, so the image entrypoint runs |
| Secret File name | `ca.pem` containing the Aiven CA in PEM format |
| Runtime secret path | `/etc/secrets/ca.pem` |
| Environment variable | `AIVEN_CA_CERT=/etc/secrets/ca.pem` (recommended explicit setting; Render default detection also supports this path) |
| Active Spring profile | `SPRING_PROFILES_ACTIVE=production` |
| HTTP port | Keep the working value; if using Render's default port, set `SERVER_PORT=10000`. The application reads `SERVER_PORT`, not `PORT` directly. |
| Health Check Path | `/readyz` for direct Docker hosting with the production profile |
| Auto-Deploy | Existing **On Commit** policy may remain. Merging into main can trigger deployment; this PR does not merge or deploy. |

**Keep the existing corrected `SPRING_DATASOURCE_URL` unchanged**, including `sslMode=VERIFY_IDENTITY`, Aiven's real hostname and port, and database `brandista`. Retain `SPRING_DATASOURCE_USERNAME=brandista_app` and the existing password/JWT secret in Render; do not paste their values into Git, PR comments or logs. No new database credentials or JWT key are needed.

Do not configure a Connector/J `trustCertificateKeyStoreUrl` that overrides the merged JVM store, or `fallbackToSystemTrustStore=false`; those connection settings override/bypass the JVM truststore used by this fix. Do not set conflicting `javax.net.ssl.trustStore*` options. There is no reason to disable certificate or hostname verification.

**Health-path distinction:** `/healthz` in this repository belongs to the separate nginx proxy, which maps it to application `/readyz`. A Render service that runs this Dockerfile directly does not run that proxy. Leaving its HTTP health check at `/healthz` will fail even after the database TLS handshake is repaired. Configure `/readyz` on the production profile; the existing route includes database readiness. This PR preserves all existing application routes rather than adding a deployment-specific alias. Without that profile, the default application's database-aware endpoint is `/actuator/health/readiness`.

Enabling the production profile retains its separate loopback management listener. Do not expose the management port as Render's public HTTP port. Keep the public application port on the existing all-interface bind.

## Verification after the approved deploy

1. Confirm the commit/image is the reviewed main revision and the Docker Command override is empty.
2. Confirm the CA setup success message appears before Spring starts. Missing/invalid CA diagnostics must stop startup; never work around them with weaker TLS settings.
3. Verify the old trust-anchor exception and `UnknownHostException` are absent. If they persist, check the actual CA fingerprint and any conflicting Connector/J/JVM truststore settings without printing credentials.
4. Confirm Hikari connects and Flyway completes normally against `brandista`; do not edit applied migrations or grant unnecessary DB privileges.
5. Confirm Render's `/readyz` HTTP health check returns 200, then check an existing public read route. Keep health details and management metrics private.
6. Recheck the configured JDBC URL still contains `sslMode=VERIFY_IDENTITY`. This retains hostname verification against the corrected Aiven hostname; this repair does not prove a live Aiven connection without an authorized deployment.

## Local verification

Build the real Dockerfile (its existing build stage runs the backend Maven tests):

```powershell
docker build --progress=plain -t brandista-render-ca:test .
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
python scripts/test-render-ca.py
```

The smoke test creates disposable synthetic CA/key material only in ignored `target/`, builds a derived test image without private keys, and runs the **actual image entrypoint** as `appuser` with a read-only root filesystem and writable `/tmp`. It verifies Render-style `root:1000` / 0640 secret-file access, all original public CA entries, one additional CA, 0700/0600 permissions, a successful synthetic server-chain trust check, unchanged unconfigured-local behavior, preserved application arguments, and startup rejection of missing/malformed/expired/non-CA files. It never contacts Aiven or sends production traffic.

Actual build, smoke-test and CI outcomes are recorded in the PR; live Render startup and the real Aiven certificate chain remain post-review deployment checks.

Sources: [Render Docker secret-file permissions](https://render.com/docs/docker-secrets), [Connector/J truststore precedence and VERIFY_IDENTITY](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html), [Render health checks](https://render.com/docs/health-checks).
