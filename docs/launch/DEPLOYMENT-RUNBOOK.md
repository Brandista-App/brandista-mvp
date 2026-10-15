# Deployment runbook — private demo preparation

Status: **BLOCKED for deployment**. No approved hosting target, domain, TLS certificate or production secrets were supplied. Docker engine is unavailable locally. Configuration parses, but container image builds and deployment smoke tests are **NOT RUN**. Do not publish this demo until the owner approves the host, scope, security controls and synthetic data.

## Existing MySQL deployment, with storefront overlay

Keep sibling paths `brandista-mvp` and `brandista-mobile`. Backend Dockerfile builds Java 21 and runs as uid 10001. The new frontend Dockerfile installs locked dependencies, builds Vite with `/api/v1`, and serves assets with nginx. Explicit COPY paths avoid importing host node_modules or environment files. `compose.demo.yaml` overlays existing MySQL/backend services; nginx proxies the same-origin API and readiness endpoint, and falls back to index.html for SPA routes. API responses use no-store; no service worker caches private data.

From backend root, use a private local shell:

```powershell
docker version
# Copy only if .env does not exist; preserve existing values and database volume.
if (!(Test-Path .env)) { Copy-Item .env.example .env }
# Edit .env locally: unique DB passwords and generated >=32-byte JWT secret.
# Set SERVER_PORT=8080 for this overlay, BACKEND_PORT to a free loopback port,
# and optionally STOREFRONT_PORT=8090. Never commit .env.
docker compose -f compose.yaml -f compose.demo.yaml config --quiet
docker compose -f compose.yaml -f compose.demo.yaml up -d --build
docker compose -f compose.yaml -f compose.demo.yaml ps
Invoke-RestMethod http://127.0.0.1:8090/healthz
```

Expected private storefront is `http://127.0.0.1:8090/`; this URL is **not running/verified**. Same-origin proxy means no browser cross-origin CORS is required. For separate Vite development set CORS_ALLOWED_ORIGINS to the exact frontend origin and VITE_API_BASE_URL to the versioned backend root. Never put secrets in VITE variables. Do not expose MySQL or backend ports publicly.

Flyway automatically migrates V1–V8 before JPA validates schema. Back up first; never edit applied migrations, use ddl-auto=update, or delete mysql_data to fix credentials. Readiness checks database connectivity. MySQL's root ping alone does not prove the app user's credentials work. Inspect redacted logs on failures. Health details remain hidden. nginx exposes `/healthz` and denies `/actuator/` externally; direct backend access remains loopback only.

## HTTPS and private hosting handoff

Owner supplies an approved isolated host/account, operator, access method, domain/TLS plan and spending/exposure authorization. Place an approved TLS reverse proxy on that host in front of loopback 8090; certificate validation, HTTP-to-HTTPS redirect and renewal must be verified on the actual domain. Forward the correct scheme; configure the trusted proxy chain before relying on forwarded headers. Do not change DNS, bind public ports or issue public certificates without explicit owner authorization. Require access restriction/abuse controls before real submissions: registration, login and partner intake currently lack rate limiting. Rotate local demo credentials; never deploy the H2 test launcher or its data.

## Backup and rollback

Preserve the named MySQL volume across normal down/up. Take an encrypted database export before migration using the application account or a least-privilege backup account. Example on the approved host (PowerShell 7; protect the output directory):

```powershell
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --single-transaction --no-tablespaces "$MYSQL_DATABASE"' > brandista-backup.sql
if ($LASTEXITCODE -ne 0) { throw 'Backup failed; do not proceed' }
```

Treat the SQL file as sensitive; encrypt, restrict access, store off-host, set retention/owner and test restoring into a separate database. Export is a procedure, **not an executed backup/restore drill**. Never overwrite the live database for a drill. Restore requires a reviewed maintenance procedure and explicit approval. Keep previous known-good image digests and frontend artifacts. Roll back application images only if compatible with the migrated schema; otherwise stop traffic and restore the reviewed backup into a replacement instance. No automated down-migration or tested rollback is claimed. Record runtime errors with request IDs if implemented; redact tokens, passwords, addresses and contact bodies from shared logs.

## Required post-deployment smoke test

On the approved HTTPS URL, verify certificate, asset paths, `/healthz`, no console/request failures, sample catalogue/brand links, isolated customer registration/login, cart mutations and persistence, logout/protected routes, authorized admin workflow and mobile layout. Confirm actuator internals/development credentials are inaccessible. Checkout/order/payment cannot pass until implemented. Record deployed URL, image digests, screenshots, database type, migrations, operator and time. Current deployed URL: **none**.
