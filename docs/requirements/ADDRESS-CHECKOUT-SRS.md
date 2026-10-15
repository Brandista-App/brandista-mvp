# Address and checkout SRS addendum

Approval basis: owner's 15 October 2026 request to “update the srs and implent this functionality”, following the proposed local COD policy. Owner manual acceptance is a separate pending step. The missing historical SRS is not reconstructed or claimed to be approved here.

| ID | Approved requirement |
| --- | --- |
| AC-ADDR-01 | Authenticated customers create, list, edit and delete only their own delivery addresses. Required fields retain existing API policy: recipient, syntactically valid phone, line 1 and city. Support province/region, optional line 2/postcode and optional delivery instructions (500 characters). UI requests province/region for new addresses. |
| AC-ADDR-02 | One scalar default-address reference per customer. First saved address becomes default; choosing another replaces it. Deleting the default chooses the most recently saved remaining address or clears the reference. Serialize mutations per customer. |
| AC-ORDER-01 | One customer order contains items from one or multiple brands. Preserve immutable brand, product ID/slug/name, unit price, quantity, line totals and complete delivery snapshots. No vendor fulfilment suborders, payouts or shipment splitting are introduced. |
| AC-ORDER-02 | COD only; order status PLACED, payment status UNPAID. Creating an order never records collected money. No payment gateway or collection-confirmation API. |
| AC-ORDER-03 | For this local MVP, delivery charge, tax and discount are exactly zero. Server calculates all PKR totals with decimal arithmetic. No client total is accepted. This is an explicit local policy, not a tax or delivery quotation for a public launch. |
| AC-ORDER-04 | Review requires an owned address, nonempty cart, eligible published products with active brands/categories, and sufficient integer stock. Server review token binds address and item snapshots/prices. A changed review requires confirmation again. |
| AC-ORDER-05 | Placement revalidates everything and atomically creates order/items, decreases inventory and clears purchased cart items. Failures leave all three unchanged. Lock shared products in ID order to prevent overselling. Existing product stock starts at zero; only explicit stock configuration makes it purchasable. |
| AC-ORDER-06 | Customer-scoped UUID Idempotency-Key with a database uniqueness constraint. Same key and request replays original order without a second stock change; different payload with same key conflicts. Client retains unresolved submission keys across retries and reloads without storing access tokens. |
| AC-ORDER-07 | Only the owning customer may read their paginated order history/details. Later address/product/brand edits or address deletion never change order snapshots. |
| AC-ORDER-08 | No customer order-status mutations are supported in this initial scope: PLACED is immutable; UNPAID remains unchanged. Fulfilment, cancellation/restocking and confirmed cash collection require a later approved workflow. No unsupported transition endpoint is exposed. |
| AC-UI-01 | Real API-backed address CRUD/default selection, checkout address selection and review, place COD, confirmation, history/details. Show loading/empty/error/validation/success states and prevent repeated submissions. Preserve the current design and responsive layout. |
| AC-LOCAL-01 | Local verification against persistent MySQL; fictional demo brands and products only for seed/testing, no existing customer/catalogue deletion. No public deployment or Android development. COD disabled by default; explicitly enabled in the local acceptance startup. |

Contract: [CHECKOUT-CONTRACT.md](../api/CHECKOUT-CONTRACT.md). Verification: [ADDRESS-CHECKOUT-ACCEPTANCE.md](../testing/ADDRESS-CHECKOUT-ACCEPTANCE.md).
