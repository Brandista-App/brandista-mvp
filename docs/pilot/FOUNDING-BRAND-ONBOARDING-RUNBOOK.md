# Founding Brand Onboarding Runbook

This runbook describes an operator-managed pilot using Brandista's current API. It does not imply a signed agreement, legal verification, actual partner participation, or live payment/fulfilment support. No merchant login or admin web console exists; an authorized Brandista operator performs the review and catalogue work.

## 1. Intake

Ask a prospective partner for:

- Public brand name.
- Contact person's name and work email.
- Public website, if available.
- A short, non-sensitive introduction.
- After initial interest: product names/descriptions, agreed PKR prices, category, current availability, and image files plus written image-use permission.

Do not collect identity documents, bank/payment credentials, passwords, or other sensitive records in the application form. The application endpoint is public and does not have rate limiting; an operator should monitor submissions and avoid sharing its contact details.

Prospective brands may submit interest at `/partners` in the customer frontend, or an operator may submit `POST /api/v1/brand-applications` using the request contract in `B0.14-api-contract.md` plus the F0.9 report. Duplicate open submissions for the same normalized contact email return `409`.

## 2. Review and approval

Only an authenticated existing `ADMIN` account may use `/api/v1/admin/brand-applications`. There is no administrator interface in the frontend.

1. List submitted applications with `GET /api/v1/admin/brand-applications`. Use this review path for founding-brand intake; the legacy direct brand-create API must not be used to bypass application review.
2. Review the business information and contact the applicant outside the application if more detail is needed.
3. Record `NEEDS_INFORMATION`, `REJECTED`, or `APPROVED` with a concise review note using `PATCH /api/v1/admin/brand-applications/{id}/review`.
4. Approval records the decision and creates a separate `INACTIVE` brand. It is an operational approval only, not legal verification.
5. Verify the profile, any agreed public-facing description and brand name, then use the existing admin brand API to activate it. If its generated slug conflicts or is invalid, resolve the slug under operator review before resubmitting approval. A closed application cannot be reviewed again; handle later contact as a new application after the prior one is closed.

Never give an applicant an admin token. Do not create an admin account or bypass role checks for onboarding.

## 3. Product intake and publication

Use `BRAND-PRODUCT-INTAKE-TEMPLATE.csv` as a collection worksheet only. There is no supported CSV importer. Transfer reviewed rows manually through the administrator product API.

The current product model supports: brand ID, active category ID, name, slug, description, PKR price with at most two decimal places, and `AVAILABLE`/`UNAVAILABLE`. It does not support SKU, images, variants, stock quantities, bulk upload, merchant ownership, or product-submission status separate from admin draft creation. Do not promise those capabilities to a partner.

1. Confirm the brand and category are active.
2. Create each product via `POST /api/v1/admin/products`; it starts as `DRAFT`.
3. Review spelling, product copy, category, price, and availability with the contact person.
4. Publish only after approval with `PATCH /api/v1/admin/products/{id}/publish`.
5. Confirm the product appears at `/api/v1/products/{slug}` and in public discovery.
6. Test cart addition only with a test customer and isolated test environment. The backend currently has no order placement or payment flow.

Product images are not stored by this backend. Do not use temporary local file paths or arbitrary remote URLs as if image upload were supported. Keep approved assets outside the product record until a durable storage decision and implementation exist.

## 4. Storefront checks and pilot operations

- Verify the public brand listing and product detail route in the customer frontend.
- Verify an inactive brand is not listed publicly and its products do not appear in public discovery.
- Verify each product is published, marked available only after the partner confirms availability, and displays the agreed PKR price.
- Reconfirm fulfilment responsibility, stock checking, shipping, returns, cancellations, refunds, customer support, and payment limitations before inviting test customers.
- Record pilot feedback, listing corrections, incidents, and outcomes with the project owner. Do not place real orders or claim successful fulfilment; checkout/order creation is not implemented.

## 5. Corrections and incident pause

- Incorrect public price or description: contact the partner, unpublish the product if customers could be misled, then update it through the admin product endpoint. Recheck the public page before republishing.
- Stock discrepancy: mark the product `UNAVAILABLE` through the admin product update API. Availability is a flag, not quantity tracking or a reservation.
- Product complaint or rights concern: unpublish the affected product promptly and record the incident for owner review. A brand may be paused with `PATCH /api/v1/admin/brands/{id}/deactivate`, which also removes its products from public discovery.
- Removal request: unpublish the product or deactivate the brand; records are retained rather than physically deleted. Confirm resolution with the requester through the agreed contact channel.
- Payment, order, refunds, and fulfilment incidents cannot be resolved in-product because those capabilities are absent. Escalate to the project owner and follow the written pilot agreement.

## 6. Local rehearsal

Use the isolated local Compose procedure from the backend README and the frontend F0.8 pilot operations runbook. Confirm Docker health and backend readiness before testing. Use test accounts and local data only. Do not point local rehearsal commands at a shared or production database.

## Troubleshooting

- `401`: obtain a current bearer token from an authorized operator account; tokens expire.
- `403`: the authenticated user is not an `ADMIN`; do not work around the role check.
- `409` on submission: an open application already exists for that email.
- `409` on approval: the brand name does not yield a unique usable slug; resolve the collision and review again.
- `400`: inspect the structured `fieldErrors` response and correct the indicated value.
- Product `409`: confirm brand/category are active and the slug is unique.
- Empty public discovery: confirm brand active, category active, product published, and availability `AVAILABLE` when testing a cart.
- Readiness failure: inspect backend/MySQL Compose status and redacted logs; do not present unavailable infrastructure as healthy.
