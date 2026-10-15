# Manual address and COD checkout guide

This is a **local MySQL acceptance environment** with fictional demo products. No online payment is made. The owner has approved implementation of the SRS addendum, but personal manual checkout acceptance remains **PENDING** until the owner completes this checklist.

## Start or reopen the application

Open [Brandista](http://127.0.0.1:5176/) or [sign in](http://127.0.0.1:5176/sign-in). Use 5176 for the current app; the old 5175 address redirects here. Keep Docker Desktop running.

If services are stopped, from PowerShell:

```powershell
Set-Location D:\uzair-brandista\repositories\brandista-mvp
.\scripts\start-m01-background.ps1
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
```

Expect status UP. Startup reuses the persistent `brandista-m01_mysql_data` volume and ignored `.env.m01`. The acceptance backend on 8082 enables COD explicitly; other environments default to disabled. The background frontend on 5176 is a current source copy from the sibling React repository under `target/m01-frontend`, with its API fixed to 8082. No H2 launcher or mock server is used.

After source changes, stop only the M0.1 Java process/terminal and run `scripts/start-m01-backend.ps1 -Build` to rebuild the jar before testing; do not run two backends on 8082. The build command skips automated tests explicitly; run `mvnw.cmd test` separately when validating source changes. Restart the frontend script after copying source updates if required. Do not stop the original unrelated 8080/3307 stack to use this environment.

Optional fictional catalogue initialization, after V1–V9 are applied:

```powershell
.\scripts\seed-m01-catalogue.ps1
```

The four DEMO products belong to DEMO Lahore Loom and DEMO Karachi Craft. Prices include DEMO Linen Shirt PKR 2499.95 and DEMO Canvas Tote PKR 1250.50. Initially seeded stock is 20 for each known fictional product. Test orders decrease it. Running the seed again **does not replenish a product that has an order history**. Existing other products start at stock zero; an authorized operator can explicitly configure stock through the ADMIN inventory API. Do not falsify stock or overwrite real catalogue data to make a test pass.

## Owner checklist

1. **Register or log in.** Use your own test email and a unique password (at least 12 Unicode characters, at most 72 UTF-8 bytes). Do not share the password in chat. Public registration creates CUSTOMER only. You can use the existing account you registered earlier.
2. **Add a delivery address.** From Account, choose Manage addresses, then Add address. Enter recipient, phone, address line 1, city and province/region; line 2, postal code and instructions are optional. Use fictional contact/delivery details for a synthetic test. Save and confirm the success message.
3. **Select a default.** The first address defaults automatically. Add another if desired and choose Make default. Exactly one default is shown. Edit an address and verify saved fields. Deleting the default selects the newest remaining address; existing orders keep their snapshots.
4. **Add two brands.** Browse DEMO Linen Shirt, add quantity 1, open Bag, change quantity to 2 and choose Update quantity. Use Keep exploring, find DEMO Canvas Tote from the other brand and add quantity 1. Return to Bag.
5. **Review cart totals.** For the unchanged example prices, 2 × 2499.95 + 1 × 1250.50 = **PKR 6250.40**. The cart may omit a trailing zero; checkout/receipts show two decimal places. If stock/prices differ after other tests, use current backend values rather than this fixed example.
6. **Proceed to checkout.** Choose Proceed to checkout. Select a saved address; your default is initially selected. If there are none, use Manage addresses and return to checkout. Choose Review order. The backend checks ownership, publication/availability, stock and current prices. Resolve any stock/address errors before proceeding.
7. **Place a COD order.** Review product/brand/quantity lines and the full delivery snapshot. This approved local scope adds zero delivery charge, tax and discount. Choose Place COD order once. No card details or payment gateway are involved. Payment remains **UNPAID**; there is no cash-collection confirmation API in this scope.
8. **Verify confirmation.** Expect Order confirmed, an order reference, status PLACED, COD/UNPAID, the correct delivery details/items/totals, and an empty cart. Record the reference. A disabled button alone is not proof of an order; confirmation is read from the persistent backend order.
9. **Open order history.** Choose My orders and View order details. Verify that only your own orders are shown. To check snapshot integrity, edit/delete the saved address used above, then reopen the order; its original delivery details remain unchanged. Product name/price/brand changes likewise cannot rewrite placed order lines.
10. **Logout, login and restart.** Sign out, sign in again and reopen My orders. Refresh intentionally ends the memory-only login session; sign in again to recover database-backed orders. For an optional restart test, stop only the M0.1 backend and restart it with the commands above. To restart the dedicated MySQL container while preserving data:

```powershell
docker compose --env-file .env.m01 -p brandista-m01 restart mysql
```

Wait for readiness UP, sign in and verify the same order reference/details. Never use `down -v`, delete the database volume, replace `.env.m01` credentials on an existing volume, or restore the pre-migration backup over current orders.

## Errors and uncertain submissions

- Insufficient stock, unpublished/unavailable products or changed prices/address produce a conflict and leave order/inventory/cart unchanged. Review again after correcting the cart/address. A review does not reserve stock.
- If a network/server response is lost, the page retains the submission key and offers **Retry submitted order**. Use that action; it reuses the exact request and retrieves the same committed order or safely places it once. You can also check My orders. Reloading requires login; the unresolved request key survives in the same tab's sessionStorage. No access token or delivery PII is stored there.
- Another customer's address/order ID returns not found. Signing out protects customer routes. Disabled database accounts cannot use checkout/history even with a previously issued token.
- No order status update, cancellation, shipment split, refund, actual collection or paid state is available. Do not treat PLACED as shipped or paid.

## Record acceptance

For each step, record PASS/FAIL and a short observation or screenshot. Include the order reference and browser size if relevant, but exclude passwords, bearer tokens and private delivery data. Agent test evidence is in [ADDRESS-CHECKOUT-ACCEPTANCE.md](ADDRESS-CHECKOUT-ACCEPTANCE.md); it is not a claim that you have personally accepted checkout.
