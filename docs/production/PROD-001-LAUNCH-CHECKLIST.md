# PROD-001 commercial launch decision

2026-10-25. **NO-GO for unrestricted commercial launch.** Merged feature work and a native Simulator build do not close operational, security, device or financial/logistics gates. No public deployment or purchase was performed.

## Required decision gates

| Priority | Gate | Responsible role | Acceptance evidence |
| --- | --- | --- | --- |
| P0 | Mobile high-severity dependency paths | Mobile/security owner | Compatible upstream remediation and repeated native regression; existing testing exception ends **2026-11-05**, not extended here |
| P0 | Unresolved backend advisory matches | Backend/security owner | Verify Tomcat fix artifact availability and Spring feature applicability/remediation; retain explicit critical-match tracking and regression before approval |
| P0 | Actual production invitation delivery | Backend/operations | Approved email/delivery adapter, recipient binding, retries without token disclosure, rate limits, audit and failure recovery |
| P0 | Device and live environment acceptance | Mobile/QA/backend | Reviewed signed Android/iPhone candidates, actual fresh-install offline tests and reachable trusted-HTTPS synthetic customer/COD tests |
| P0 | Payment/COD operating rules | Business/finance/merchant operations | Agreed collection responsibility, remittance evidence, reconciliation, refund/cancellation/returns policy and truthful customer notices |
| P0 | Secure operated infrastructure | Operations/security | Approved TLS/DNS/ingress, runtime-only DB grants, secrets, monitored backups and successful restore, on-call owner |
| P0 | Tested capacity for the chosen environment | Backend/operations | Production-like staged and sustained tests with real deployment proxy/TLS, monitored saturation and intact orders/stock; do not extrapolate laptop VUs |
| P1 | Public onboarding release decision | Web owner | Review frontend PR3 and deploy the selected audited bundle only after approval |
| P1 | Product media pipeline | Backend/web/merchant | Approved image upload/reference contract, size/type validation, access policy and CDN delivery |
| P1 | Privacy/support/merchant agreements | Business/legal owner | Actual jurisdiction-appropriate review, retention/access policies, customer/merchant support ownership; this engineering audit is not legal advice |
| P1 | Image/SBOM/advisory automation | Security/release | Current runtime image scan and complete dependency assessment; no inference of clearance from unit tests |

## Payment and logistics capability boundaries

Current COD creates immutable PKR order/delivery/item snapshots, enforces server totals and stock deduction, persists customer-scoped idempotency keys, and creates per-brand fulfillment. Supported merchant statuses are PLACED → ACCEPTED → PACKED → SHIPPED → DELIVERED with version checks/events. Parent financial state remains COD/UNPAID; a merchant clicking DELIVERED is **not payment reconciliation**. The database deliberately enforces zero delivery charge/tax/discount in the current contract. Do not advertise shipping/tax/payment features that are not implemented or change these constraints casually.

| Area | Missing requirements / next implementation |
| --- | --- |
| Gateway payments | Approved provider, merchant-of-record/account ownership, currency/support, server-created payment intents, webhook signature verification, amount/order binding, event replay/idempotency, uncertain-result recovery and sandbox acceptance |
| Reconciliation | Immutable financial ledger, provider/courier statements, paid/partial/failed/disputed state rules, daily exception queue and audit controls |
| Refunds | Eligibility/authorization, partial and full amounts, idempotent provider operation, webhook recovery, ledger reversal and customer communications |
| Cancellations | Allowed stage/actor rules, concurrent fulfillment protection, inventory release exactly once, refund/financial interaction and audit events |
| Merchant settlement | Payout identity/KYC responsibility, ledger allocations across brands, schedules, holds/disputes, reconciliation and approved payout controls |
| Commissions | Agreed versioned rates, immutable order-line fee snapshots, rounding/tax rules and settlement/refund accounting |
| Couriers | Approved carrier contracts, address/serviceability/rates, label credentials, shipment IDs per brand, duplicate-safe booking, signed webhooks/polling and failure handling |
| Tracking | Provider status mapping, customer-owned visibility, public tracking privacy, exception handling and evidence of delivery |
| Returns | Authorization, per-line quantities/reason/condition, warehouse acceptance, restock/refund decisions and duplicate/concurrent request prevention |

## Prioritized milestones

1. **PROD-002: close launch security and delivery gates.** Compatible mobile advisory remediation, full backend/image SBOM scanning, approved invitation transport, secret/grant proof and account-aware distributed abuse controls. Do not renew the November 5 exception silently.
2. **OPS-001: approved staging and recovery.** Owner-approved cost/region/TLS/private DB; deploy by reviewed procedure, connect private monitoring to real recipients, prove least privilege, backup/restore and rollback. Repeat sustained load on that exact topology; test the production proxy separately from direct-backend capacity.
3. **ACCEPT-001: physical devices and full marketplace pilot.** Capture Android and iPhone offline-first launch, cart/retry/Keychain/keyboard/accessibility, then customer/merchant workflows using disposable data on the approved private backend. Select the public onboarding release after PR3 review.
4. **COD-OPS-001: controlled manual pilot.** Approve explicit COD collection/remittance, merchant/customer support, cancellation/returns and courier operating procedures. Start with a stated order/brand limit and monitoring/stop criteria, not an invented user-capacity promise.
5. **FIN-001 / SHIP-001: approved integrations.** Implement provider-neutral ledgers/state machines first; select providers only with owner approval, integrate sandbox flows and then obtain a separate live-funds/logistics sign-off. No unapproved gateway or live funds are touched here.

The owner can review the PROD-001 PR and choose accountable owners for these gates now. Launch remains blocked until evidence is attached and the owner explicitly approves the release and spending scope.
