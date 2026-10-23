# FINAL-001 production-like deployment preparation

**Not deployed or production-certified.** This separate template preserves the existing local Compose stack and V1–V14 backend. It requires owner-provisioned private MySQL, a real approved API hostname, certificate/private key and injected credentials. No service URL, privileged account or production secret is supplied. Both API and database runtime acceptance remain blocked until these are provisioned.

The existing non-root Java21 Dockerfile is reused. Backend ports and MySQL are not published. The nginx TLS listener binds only to **127.0.0.1:8443**, so applying this template does not establish a public service. Public ingress/firewall/DNS changes remain a separate approved operation. The existing nginx image family is retained for preparation; pin reviewed backend/base/nginx image digests and scan them before deployment.

Copy .env.example into an access-controlled, ignored operator file, fill the empty values using the approved secret mechanism and validate only:

```powershell
docker compose --env-file <operator-env-file> -f deploy/production-like/compose.yaml config --quiet
```

Do not print resolved configuration to shared logs: it contains secrets. Before approving deployment:

1. Provision isolated MySQL with private networking, least-privilege credentials and `sslMode=VERIFY_IDENTITY`. Verify hostname/certificate chain using the JVM trust roots or an approved mounted truststore. Do not use `useSSL=false`, `allowPublicKeyRetrieval=true` or certificate bypass flags for that remote service. Reject a JDBC URL without the required TLS mode during operator review.
2. Supply a trusted HTTPS chain and restricted private-key file for the exact hostname. Test certificate validation and renewal on the approved ingress. Restrict host administration; configure trusted proxy handling deliberately. Do not expose actuator internals, database ports or the Docker socket.
3. Inject JWT/database secrets outside source control and outside the APK. Choose issuer/audience, rotation and access policy. Set exact browser CORS origins; do not use a wildcard. Restrict abuse/registration/login at the approved edge before inviting real customers.
4. Back up before applying migrations. Flyway V1–V14 runs on startup with Hibernate validation. Provision an encrypted off-host backup schedule, retention/operator ownership and a demonstrated restore into a separate replacement database. Never delete or reset an existing volume as a repair. Keep prior reviewed images and assess schema compatibility before rollback.
5. Configure real HTTP readiness probes against `/healthz` (database included) and liveness privately against `/actuator/health/liveness`; a running container or Java process alone is not readiness. Restrict health responses to status only. Configure redacted operational logging, monitoring, alerting and incident ownership.
6. Keep COD disabled until real environment tests cover login/session, catalogue, stock, owner/tenant isolation, address snapshots, cross-brand orders, concurrent/lost-response idempotency and order history. Then explicitly enable COD through configuration. Merchant fulfillment is separate from payment collection/settlement.

Once a separately approved deployment exists, verify TLS without bypasses. An operator can use `curl --resolve` for a local SNI check with the approved hostname and chain, then test the final public URL. Record the actual certificate, reachable catalogue contract, backend commit, image digests, MySQL migration state and disposable acceptance results. Only then rebuild the customer APK with that verified HTTPS `/api/v1` endpoint.

FINAL-001 safe checks: Compose syntax/interpolation can be checked without a Docker daemon using non-secret parse-only placeholders. No image build, TLS handshake, remote MySQL connection, container startup, public deployment or backup/restore is implied by that check. Current backend automated tests: 190 pass. Existing historical isolated MySQL acceptance is recorded in the mobile TEST-002 report and is not public deployment evidence.
