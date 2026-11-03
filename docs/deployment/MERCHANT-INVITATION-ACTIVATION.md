# Merchant invitation and activation

## Delivery and authorization contract

Approval and invitation/outbox creation commit together. If delivery is not configured,
new approval fails safely instead of claiming an invitation was delivered. Previously
approved records are unchanged and can receive an invitation through their existing
Super Admin application page; never approve them again. No migration sends email.

New invitations expire exactly 48 hours after issue. Each uses a fresh 256-bit random
nonce and HMAC-SHA256 with a separate 256-bit backend-only key. Only the SHA-256 token
hash is persisted as a token verifier. The outbox stores an invitation reference;
neither plaintext nor encrypted bearer tokens are in the database. The nonce alone
cannot recreate a token. Keep the derivation key outside the DB and use the same key
on every backend instance. Retain it while queued invitations remain; changing it
blocks queued sends with TOKEN_KEY_CHANGED. Already sent token hashes remain valid.

An HTTPS link carries the token in its fragment, which is not sent to the web server.
The registration page reads it into component memory and removes the fragment. No
token/password is written to browser storage, analytics, or logs. A POST verifies
the invitation and reveals the fixed recipient, brand, expiration, and terms version.
Completion accepts no brand/application identifier. It locks security state and the
invitation, validates consent and password, creates or verifies the existing account,
assigns the existing brand membership, consumes the token, records terms consent,
and activates the approved brand in one transaction. Login remains a separate step.
No ADMIN or SUPER_ADMIN platform grant is created. Existing accounts require their
current password; this flow cannot reset passwords or re-enable disabled accounts.

Approval itself does not publish products. Brand activation makes the brand eligible
for the existing public catalogue rules; product publication is still explicit.
The legacy authenticated acceptance endpoint now also requires `termsAccepted:true`
and `termsVersion:"merchant-v1-2026-11"`; update any operator scripts before use.

## Exact deployment requirements (execution requires approval)

1. Review backend and admin PRs together. Back up production and verify the restore
   procedure. Apply additive Flyway **V16** using the existing migration identity;
   never edit V1–V15 or delete/review existing applications, users, or grants.
2. Use the existing Java 21 runtime, MySQL 8.4 and deployment memory limits. V16 adds
   delivery, terms, and public registration budget fields/tables without modifying
   existing account/commerce records. Runtime identity needs SELECT/INSERT/UPDATE/
   DELETE on the new tables and UPDATE of the added invitation fields. Keep DDL
   limited to the migration identity as in the existing deployment runbook.
3. Configure a Resend account and a dedicated sender domain/subdomain controlled by
   Brandista. Add its provider-issued SPF and DKIM records, set an appropriate DMARC
   policy, and confirm the domain is **verified** in Resend. The backend retrieves
   the configured domain before each send and checks sender-domain equality. No DNS
   or provider configuration has been changed by this implementation.
4. Backend-only settings (never VITE_/EXPO_PUBLIC_):

   | Variable | Required value |
   | --- | --- |
   | `MERCHANT_EMAIL_ENABLED` | `true` only after approved staging delivery validation |
   | `MERCHANT_AUTO_INVITE_ON_APPROVAL` | `true` (default); approval and invitation are atomic |
   | `MERCHANT_INVITATION_TOKEN_KEY_BASE64` | Base64 of 32 cryptographically random bytes, separate from JWT secret |
   | `RESEND_API_KEY` | Backend secret with email send/read and domain-read access for this account |
   | `RESEND_DOMAIN_ID` | The verified sender domain's ID |
   | `MERCHANT_SENDER_EMAIL` | Plain mailbox on that exact domain, e.g. `partners@mail.brandistaapp.com` |
   | `MERCHANT_REGISTRATION_ORIGIN` | Actual admin HTTPS origin, no trailing slash/path/query, e.g. `https://admin.brandistaapp.com` |
   | `CORS_ALLOWED_ORIGINS` | Include the exact registration/admin HTTPS origin, preserve existing permitted clients |

   Existing datasource/JWT/TLS settings stay required. Configure secret values through
   the hosting secret store after approval. Never include credentials in PRs.
5. Deploy backend first, then admin built with `VITE_API_BASE_URL` pointing to the
   deployed HTTPS `/api/v1`. SPA fallback must serve `/accept-invitation` and
   `/merchant-terms`; preserve Referrer-Policy no-referrer and disable analytics/session
   replay on registration. Review the merchant terms text with Brandista's authorized
   owner before launch; commercial fees/settlement remain the separate brand agreement.
6. Background processing runs inside the backend every 10 seconds; delivery status
   polls every minute in batches, then every five minutes per email for up to seven
   days. Allow HTTPS egress to `api.resend.com`. Multiple instances use DB leases and
   the provider idempotency key for the same invitation. Configure reverse-proxy/WAF
   limits for public verify/complete endpoints. The built-in DB budget is per direct
   peer IP (30 verifications / 5 completions per minute), ignores spoofable forwarded
   headers, and may be shared by users behind the same proxy; configure ingress limits
   to protect individual clients and size this budget for the deployment topology.

## Delivery states and retry limits

QUEUED/RETRY/SENDING are not delivery success. PROVIDER_ACCEPTED means Resend returned
an email ID, not inbox receipt. DELIVERED is only recorded from the provider's email
status; it still does not prove the recipient read it. FAILED includes provider
rejection/bounce/complaint and exhausted retries. NOT_TRACKED labels older/development
invitations; never infer they were emailed. Errors expose only safe codes.

Sending retries at most six attempts with bounded exponential delay. A stable
idempotency key is reused. Resend retains keys for 24h, so an ambiguous send is never
retried after 23h from its first attempt; request a new invitation after inspection.
Do not edit sender/origin/template or the approved brand name while sends are pending:
provider idempotency requires the same payload. Revoked, accepted, and expired
invitations are cancelled before dispatch. A send already in flight may still reach
the recipient; revocation immediately invalidates its link. Resend rotates the token
and revokes the old invitation atomically, with a one-minute cooldown. A repeated
approval/create/resend cannot silently enqueue the same invitation twice.

## Staging delivery test — prepared, not executed

Automated tests use synthetic `example.invalid` identities and a local provider fixture.
They do not prove real email delivery. No real invitation or staging email was sent.
After explicit approval and staging configuration, use an operator-controlled inbox:

```powershell
# Supply STAGING_SUPER_ADMIN_EMAIL / STAGING_SUPER_ADMIN_PASSWORD via the secret store.
python scripts/test-merchant-staging.py --api https://staging-api.brandistaapp.com/api/v1 --staging-host staging-api.brandistaapp.com --recipient APPROVED_TEST_INBOX --allow-send-staging-email
```

This creates one clearly labelled synthetic staging application, approves it once,
checks exactly one invitation, and waits for provider-reported DELIVERED. It never
prints bearer tokens, registration links, passwords, or raw provider errors. Confirm
the actual inbox, sender identity, spam placement, branding, 48-hour deadline and
link host manually; complete registration, sign in as merchant, and check brand
isolation. Record provider ID, timestamps, IDs, and sanitized screenshots. Do not
label staging delivery passed until both provider and inbox checks succeed.

## First approved brand: TSC (The Sindhi Culture)

1. After deployment approval and successful staging validation, open Super Admin →
   Brand applications → TSC. Verify the exact application ID, assigned brand ID,
   APPROVED status and applicant-supplied contact email. Do not guess or overwrite
   the recipient. Do not re-review an already approved TSC application.
2. Inspect invitation history first. If a current invitation exists, inspect its
   delivery status; if ACCEPTED, sign in instead of creating another account. If TSC
   was approved before this feature and has no invitation, obtain explicit approval
   to send the real invitation, then choose Create invitation once and confirm.
3. Check QUEUED → PROVIDER_ACCEPTED → DELIVERED. Inspect the provider's ID/status and
   ask the authorized recipient to confirm receipt. Approval/queueing alone is not
   success. For FAILED inspect credentials/domain/provider status without copying
   secrets; resend only after resolving the cause and confirming the intended email.
4. The TSC recipient opens the private HTTPS link, clicks Verify invitation, checks
   TSC and their fixed email, creates a password (or enters their existing password),
   reads/accepts merchant terms, and activates the account. No brand can be selected
   or substituted. The link becomes unusable immediately after completion.
5. Sign in with Merchant · assigned brands. Verify TSC is assigned and its catalogue
   workspace is accessible. Validate product creation/inventory with a draft only;
   do not publish a real product or place an order without separate authorization.
   A different brand ID and all Super Admin endpoints must return 403.
6. Confirm ACCEPTED status, correct user/brand membership, terms version/time, ACTIVE
   brand status, and audit IDs. Retry the consumed link: it must fail. Revoke an
   unused test invitation and verify failure; test expiry only on synthetic staging
   records, never by editing TSC's production timestamps.

## Sources

- https://resend.com/docs/dashboard/emails/idempotency-keys
- https://resend.com/docs/api-reference/emails/retrieve-email
- https://resend.com/docs/api-reference/domains/get-domain
