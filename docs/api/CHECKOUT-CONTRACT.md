# Customer addresses and COD checkout contract

Scope: [approved SRS addendum](../requirements/ADDRESS-CHECKOUT-SRS.md), migration V9. Base API `/api/v1`. All routes below require a valid CUSTOMER bearer token except existing public catalogue routes. Owner identity always comes from the signed JWT subject. Cross-customer address/order IDs return the same 404 as a missing ID. Checkout/history also verify that the database account is still an enabled CUSTOMER.

## Addresses

Existing `/customers/me/addresses` CRUD is preserved. POST creates (201), GET lists, GET `/{id}` reads, PUT `/{id}` replaces, PATCH `/{id}` changes supplied fields, DELETE `/{id}` deletes (204).

```json
{
  "recipientName": "Demo Customer",
  "phoneNumber": "+92 300 1234567",
  "addressLine1": "Fictional House 1",
  "addressLine2": "",
  "city": "Lahore",
  "province": "Punjab",
  "postalCode": "54000",
  "deliveryInstructions": "Ring once"
}
```

Recipient (120), phone (25, 7–15 digits with common punctuation), line 1 (255), city (100) are required. Province/region (100), line 2 (255), postcode (20) and instructions (500) are optional in the compatible API. The new form asks for province/region. Blank optional values are normalized to null. PATCH requires at least one field; empty strings clear optional values. Unknown fields cannot assign an owner.

Responses retain existing fields/timestamps and add `deliveryInstructions` and `isDefault`. `PUT /customers/me/addresses/{id}/default` has no body and returns that address (200). First address becomes default; replacing the default is serialized on the customer row. Deleting it selects the newest remaining address or null. The scalar users.default_address_id prevents multiple defaults; database FK clears a deleted reference. Placed orders have independent snapshots and no FK to a saved address, so address deletion does not delete order history.

## Inventory

Products have nonnegative `stockQuantity`, default zero for all existing/new products. It is returned by public/admin product DTOs. Existing AVAILABLE/UNAVAILABLE and publication/brand/category status checks still apply. `PUT /admin/products/{id}/stock` accepts `{"stockQuantity":20}`; ADMIN only, integer required, range 0–2147483647. It uses the same product locking path as checkout. No merchant tenancy or inventory reservations are added. Cart display prices remain provisional; stock is authoritatively checked at review and placement.

The local insert-only catalogue seed initializes stock 20 only for the four known fictional demo slugs with zero stock and no historical order lines. It never refills an already purchased product or edits other catalogue rows. It now requires migrations V1–V9.

## Review

`GET /customers/me/checkout/config` returns `{"codEnabled":true,"currency":"PKR"}` in the acceptance environment. `COD_CHECKOUT_ENABLED` defaults to false; the local M0.1 startup explicitly enables the approved local policy.

`POST /customers/me/checkout/preview` accepts only the selected `addressId`:

```json
{"addressId":12}
```

Returns (200): `quoteToken`, complete `deliveryAddress` snapshot, `items`, `subtotal`, `deliveryCharge`, `tax`, `discount`, `total`, `currency:"PKR"`, `paymentMethod:"COD"`. Each line has productId, productSlug, productName, brandId, brandName, quantity, unitPrice and lineSubtotal. The server calculates decimal totals; charges/tax/discount are exactly zero under the approved local policy. The opaque SHA-256 review token binds the current address ID/snapshot, sorted product/brand snapshots, quantities and prices. It is a change-detection token, not an authorization credential. Placement recomputes the same values from the database; it never trusts client pricing.

Review validates owned address, nonempty cart, current eligible products and stock, but reserves nothing. A review has no time expiry; it remains usable only while its bound state remains unchanged and all eligibility/stock checks still pass. A change requires a new review.

## Place order

`POST /customers/me/orders`, with:

```text
Authorization: Bearer <access token>
Content-Type: application/json
Idempotency-Key: <UUID>
```

```json
{"addressId":12,"quoteToken":"<64 lowercase hexadecimal characters from review>"}
```

The only payment method is server-selected COD. Client totals, payment states, owner IDs and inventory values are not used. A new order returns 201. A repeated key with the identical address/review returns the original order with 200, including after its source address is deleted or cart is empty. A different address/review using the same successful key returns 409. Keys are scoped per customer and retained with the order under a unique database constraint; they do not expire in this scope. Failed transactions create no order/key record.

Order response: id, status, paymentMethod, paymentStatus, currency, subtotal, deliveryCharge, tax, discount, total, deliveryAddress, items and createdAt. Status is **PLACED**, payment method **COD**, payment status **UNPAID**. Timestamps are UTC LocalDateTime values without a suffix; the frontend converts them from UTC for display. The order ID is the customer-visible reference. An order can contain multiple brands; there are no fulfilment suborders or payout records.

## Transaction and retries

1. Lock the customer row, serializing cart/address/default/checkout mutations.
2. Verify enabled customer and look for an already committed key. Matching retries replay the original result before checking current cart/source address.
3. Read the owned address/current cart; lock current product/brand/category rows in product-ID order; check eligibility, stock, prices and review token. Reject totals outside the supported DECIMAL(19,2) range.
4. Decrease stock, persist header and immutable item/address snapshots, and clear cart items in **one transaction**. Any later exception rolls everything back.

Competing customers cannot oversell; simultaneous same-customer submissions serialize. The frontend disables repeated clicks using a synchronous guard. Before submission it stores only the UUID, address ID and review token in customer-scoped sessionStorage, never tokens or delivery PII. Network/server failures retain that exact request/key; retry remains possible even if the cart was already cleared by a committed response the browser lost. Explicit 4xx validation/conflict responses invalidate the current review. History offers a separate way to verify the outcome. If browser sessionStorage is unavailable, only in-memory retries survive; refresh still requires sign-in under the existing authentication design.

CORS explicitly permits Authorization, Content-Type and Idempotency-Key only for configured origins. It does not allow wildcard origins or cookie credentials.

## History and errors

`GET /customers/me/orders?page=0&size=20` returns the normal `content/page/size/totalElements/totalPages` page model, newest first (ID breaks timestamp ties). Size range 1–100. `GET /customers/me/orders/{id}` returns the owned immutable order. Another customer's IDs are never disclosed. Later brand/product changes and address updates/deletion do not change these snapshots.

| Status | Meaning |
| --- | --- |
| 400 | Invalid fields, malformed/missing key, invalid pagination or stock value |
| 401 | Missing/invalid/expired authentication |
| 403 | Wrong role, disabled checkout/history account or unapproved browser origin |
| 404 | Missing/non-owned address or order |
| 409 | Empty cart, disabled COD, ineligible product, insufficient stock, changed review, incompatible key reuse or retryable lock contention |
| 500 | Unexpected failure; transaction rolls back. Network/server outcomes should be retried with the same key |

Existing safe ApiErrorResponse bodies provide message and fieldErrors where applicable. No passwords, payment details or other customers' records are returned.

## Explicit limits

The only supported order/payment states are PLACED/UNPAID. No status-transition API is exposed: fulfilment, cancellation/restocking, refunds and confirmed cash collection require a later approved workflow. No gateway, money collection, public deployment or Android client was implemented. Zero local charges are not a public pricing/tax policy. The full historical SRS is still absent; this contract implements the newly approved addendum.
