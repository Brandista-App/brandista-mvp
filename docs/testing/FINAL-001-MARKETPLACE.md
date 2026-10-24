# FINAL-001 revised — complete synthetic marketplace HTTP acceptance

The new test `BrandInvitationIntegrationTests.completeSyntheticMarketplaceJourneyKeepsTwoBrandsIsolatedThroughFulfillment` uses the existing test-only platform custodian and captured-delivery fixture. It does **not** run the operator provisioner or create a privileged account in any running demonstration/production environment. No application source, API, permission, migration or business rule changes.

The existing fixture first submits and approves brand A through actual HTTP. The scenario then:

1. Issues and accepts A's invitation, then explicitly activates A.
2. Submits/reviews/approves brand B, issues and accepts B's invitation and explicitly activates B.
3. Verifies each recipient sees one assigned brand and cross-brand catalogue access is rejected.
4. Creates and edits each brand's product through merchant HTTP APIs, sets stock to 20, publishes and verifies public discovery.
5. Registers an ordinary customer through HTTP, creates a synthetic saved address and adds both brands to the customer cart.
6. Requests the real checkout quote and submits an idempotency-keyed COD order. It asserts PKR500 total, COD and UNPAID state.
7. Verifies each merchant sees only its own order lines; the other merchant and customer are denied foreign merchant access.
8. Advances only A through ACCEPTED → PACKED → SHIPPED → DELIVERED using expected versions, verifies B remains PLACED, financial payment state remains UNPAID and stock is deducted exactly once (18/19 remaining).

Run with Java21 from the backend repository:

```powershell
./mvnw.cmd '-Dtest=BrandInvitationIntegrationTests#completeSyntheticMarketplaceJourneyKeepsTwoBrandsIsolatedThroughFulfillment' test
```

The default uses a dedicated test H2 database and random loopback HTTP port. It is a real Spring HTTP/database integration test; invitation delivery is a **test capture**, not verified email. It is not a browser, physical-device, production or publicly reachable backend PASS.

For MySQL verification, the existing `scripts/verify-admin-pr4-mysql.ps1` creates only a new disposable, labelled, ephemeral-loopback MySQL container and runs the invitation class including this scenario. Its existing DynamicPropertySource rejects arbitrary/production database URLs. The existing PR7A MySQL script independently covers fulfillment/tenant/concurrency and migration integrity. Do not substitute an existing database or relax those guards. Their test fixtures do not establish usable operator-managed demonstration accounts.

Current-run evidence is retained outside Git in `D:\Brandista Workplace\FINAL-001-REVISED-evidence`; final results are included in the release handoff. No secrets, invitation payloads, passwords or bearer tokens are published. Use the existing PR2 owner-supervised procedure for any actual platform custodian; this test is not a provisioning shortcut.
