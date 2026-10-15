# Browser QA — 15 October 2026, Asia/Karachi

**PASS for the supported local customer journey; BLOCKED for MySQL/full purchasing/deployed acceptance.** Tested the real React application in the Codex in-app browser against the real Spring HTTP API on a dedicated file-H2 database. No fetch interception, mock API, fake payment or fabricated screenshot was used.

Actual tested storefront: `http://127.0.0.1:5175/`. API: `http://127.0.0.1:8081/api/v1`. Earlier pre-existing storefront at 5173 showed genuine API-unavailable errors; its processes were preserved. Backend port 8080 was occupied. The isolated storefront copies current source and locked dependencies into backend target/launch-frontend; original source repairs were made in brandista-mobile.

| Journey/check | Result | Actual observation |
| --- | --- | --- |
| Homepage | PASS | SAMPLE Studio, Sample clothing and SAMPLE Linen Shirt rendered from HTTP responses |
| Category navigation/detail | PASS | Category link selected categoryId=1; product detail showed PKR 2499.95 and availability |
| Brands/storefront | PASS | Active brand list -> SAMPLE Studio page -> brand-filtered product; replaced placeholder |
| Registration | PASS | Synthetic browser-sprint-20261015@example.test registered and redirected to sign-in with success notice |
| Login/account | PASS | Real credentials returned authenticated customer identity and saved-address empty state |
| Guest cart guard | PASS | Guest add displays sign-in prompt; authenticated login returns to requested product |
| Add/update/remove/re-add | PASS | Added quantity 1; update to 2 showed server subtotal 4999.90; remove displayed empty state; re-add restored quantity 1 |
| Checkout | BLOCKED | Checkout unavailable is disabled; no order backend/schema exists. No purchase attempted |
| Logout/protected cart | PASS | Sign out from cart navigated to sign-in; subsequent login restored the saved cart |
| Session reload | PASS for existing design | Full document navigation clears the in-memory token; session restoration is not implemented |
| Persistence after server restart | PASS, supplementary API check | Same browser-created account could log in; cart still held SAMPLE Linen Shirt, subtotal 2499.95. File H2 only |
| Merchant intake | PASS | Synthetic SAMPLE Browser Intake submitted through browser; real received-for-review success state |
| Admin web workflow | BLOCKED | No admin console exists; approval/activation/product publication separately passed real HTTP API smoke using test admin |
| Mobile navigation | PASS | Menu expanded/collapsed, exposes navigation and sign-out |
| Browser console | PASS, bounded observation | `tab.dev.logs` returned no warning/error entries during successful local flow; not a complete HAR/performance audit |
| Missing images | NEEDS REVIEW | Existing artwork is CSS, not product photos. No broken rendered image was observed; real asset pipeline missing |
| Real-browser keyword/sort/paging permutations | NOT RUN | Automated regression coverage exists; category and detail paths were exercised in browser |
| All-route keyboard/accessibility audit | NOT RUN | Labelled fields and semantic controls used; no full accessibility certification |
| Deployed HTTPS/browser smoke | NOT RUN | No deployment or approved URL |

## Responsive evidence

Homepage and populated cart were checked at each size below using actual browser viewport overrides and DOM scroll-width measurements. No horizontal overflow was detected. Mobile cart and desktop homepage screenshots were visually inspected; other screenshots are supporting captures, not claims of complete visual review of every route.

| Viewport | Home overflow | Cart overflow | Cart screenshot |
| --- | --- | --- | --- |
| 360 x 800 | none | none | [cart](screenshots/cart-360x800.jpg) |
| 390 x 844 | none | none | [cart](screenshots/cart-390x844.jpg) |
| 768 x 1024 | none | none | [cart](screenshots/cart-768x1024.jpg) |
| 1366 x 768 | none | none | [cart](screenshots/cart-1366x768.jpg) |
| 1920 x 1080 | none | none | [cart](screenshots/cart-1920x1080.jpg) |

Also captured: [mobile homepage](screenshots/home-390x844.jpg), [desktop homepage](screenshots/home-1366x768.jpg), [repaired brand storefront](screenshots/brand-390x844.jpg), and [final desktop storefront viewport](screenshots/demo-1366x768.jpg). Full-page screenshots include vertical scrolling content; scrollbar width can reduce document width relative to the requested viewport. Cart screenshots show the re-added quantity 1; the earlier quantity-2 subtotal was verified in the accessibility state, not represented by those screenshots.

No network HAR was exported. Successful UI responses and server/API assertions verify the listed calls; console logs alone do not prove every request succeeded. Initial API connection errors before startup were genuine and resolved on the isolated runtime. No unexpected redirect or auth loop was observed in successful supported flows. Repeat all acceptance steps against MySQL and the approved HTTPS host before treating this as deployment QA.
