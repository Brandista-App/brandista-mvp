# Security baseline

This document describes the current API authorization baseline. It is an implementation record, not a production security certification.

## Route access policy

Only these current routes are public:

| Route | Access |
| --- | --- |
| `GET /actuator/health` | Public; health details are disabled |
| `POST /api/v1/registrations` | Public; creates a `CUSTOMER` account only |
| `POST /api/v1/auth/login` | Public; issues a short-lived access token after successful authentication |
| `GET /api/v1/brands` | Public; returns active brands only |

Everything else requires a valid bearer token by default. Future public catalogue reads must be added as explicit, narrowly scoped route rules and must expose only published/active data.

| Route namespace | Required access |
| --- | --- |
| `/api/v1/admin/**` | `ADMIN` role; administrative write routes must use this namespace |
| `/api/v1/customers/**` | `CUSTOMER` role, plus resource ownership checks in the business/service layer |
| Any other route | Authenticated bearer token; role-specific access must be explicitly added |

Method security is enabled for sensitive operations to add a second authorization boundary when those operations are implemented. Do not rely on request path rules alone for sensitive business actions. Customer ownership must be checked against the authenticated subject in the service layer; a matching customer role does not prove ownership.

## Tokens and browser security

The API is stateless and uses Spring Security's JWT bearer-token support. The access token is signed with HS256, expires after 15 minutes by default (maximum one hour), and is validated for signature, algorithm, issuer, audience, and expiry on each protected request. The signing key is supplied through `JWT_SECRET_BASE64` and must decode to at least 32 random bytes. Keep it outside source control and use a secret manager for deployed environments.

There are no server sessions, cookies, refresh tokens, logout revocation, or token deny-list. Discarding a token at logout does not invalidate a copied token; it remains usable until expiry. An account becoming disabled also does not revoke an already-issued token. Key rotation and a revocation approach are needed before the product requires earlier invalidation.

CSRF protection is disabled because the current API authenticates with an explicit `Authorization: Bearer` header and does not authenticate using browser-managed cookies or sessions. Browsers do not automatically attach that header to cross-site form requests. If cookie-based authentication is introduced, re-enable and configure CSRF protection before using it.

CORS is restricted to origins configured in `CORS_ALLOWED_ORIGINS` as a comma-separated allow-list. The default is empty, so cross-origin browser requests are denied until an origin is configured. Credentials are not allowed; clients use bearer headers. Allowed methods and headers are explicitly limited in the security configuration.

## Current gaps

- The SRS authentication identifier decision is unavailable; email-first remains a proposal.
- Registration and login rate limiting is not implemented.
- There are no catalogue, administrative business-write, or customer-private-resource endpoints yet. Ownership checks must be implemented with those resources.
- No production deployment profile, secret rotation workflow, refresh-token system, or immediate token revocation exists.
- Apply security review and operational monitoring before deployment; this baseline does not certify the application as production-ready.
