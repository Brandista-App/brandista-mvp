# Client demonstration — 5–10 minutes

Readiness: **local catalogue/auth/cart demonstration only**. It does not accept purchases. All SAMPLE brands/products are fictional, CSS artwork is illustration, and availability is a flag rather than stock. No brand partnership, product photo rights or actual inventory is represented.

## Reproduce the verified local H2 setup

PowerShell in backend root (Java 21 and cached/downloadable Maven dependencies required):

```powershell
$env:MAVEN_OPTS='-Duser.home=C:\Users\DELL' # host-specific existing cache
.\scripts\start-local-demo.ps1
```

This binds only 127.0.0.1:8081, loads main application.yml, applies migrations to `target/launch-demo`, generates a JWT key and seeds SAMPLE Studio/SAMPLE Linen Shirt at PKR 2499.95. It creates customer@demo.brandista.test and admin@demo.brandista.test with the generated password saved only in ignored `target/launch-demo-password.local`. Never display that file on a shared screen or use those accounts outside this isolated demo. Existing users are not password-reset on restart. The launcher is under src/test and excluded from the production jar.

The original frontend has a locked native dependency on this host. The verified copy is backend `target/launch-frontend`, installed using the sibling package-lock.json and copied source/config files. To recreate without touching existing frontend processes:

```powershell
New-Item -ItemType Directory -Force target/launch-frontend | Out-Null
Copy-Item ../brandista-mobile/src target/launch-frontend -Recurse -Force
Copy-Item ../brandista-mobile/package*.json,../brandista-mobile/index.html,../brandista-mobile/vite.config.ts,../brandista-mobile/tsconfig*.json,../brandista-mobile/eslint.config.js target/launch-frontend -Force
Set-Location target/launch-frontend
npm.cmd ci --ignore-scripts --offline --no-audit --no-fund
# If cache is unavailable, rerun without --offline with permitted registry access.
Set-Content .env.local 'VITE_API_BASE_URL=http://127.0.0.1:8081/api/v1'
npm.cmd run dev -- --host 127.0.0.1 --port 5175 --strictPort
```

Use a fresh copy directory if repeating Copy-Item into an existing src directory; the sprint's copy already exists and has current source. The clean-room copy is verification tooling, not an independent product fork. Once the original dependency lock is resolved, normal development remains in brandista-mobile.

## Demonstration sequence

1. Open `http://127.0.0.1:5175/`. Explain the local H2/sample boundary before showing data.
2. Show SAMPLE Studio and Sample clothing. Open the brand storefront, then SAMPLE Linen Shirt. Show PKR price and the explicit no-checkout note.
3. Register an isolated example.test email or sign in as the seeded customer. Use in-app links: refreshing clears the in-memory token by design.
4. Add the shirt, open Bag, set quantity 2 and Update quantity. Server subtotal should be PKR 4999.90. Remove and re-add; show the empty and populated states.
5. Point out disabled Checkout unavailable. No order confirmation, payment, delivery promise or stock reservation is demonstrated.
6. Sign out (mobile: expand menu). The protected cart redirects to sign-in. Sign back in and show saved cart persistence.
7. Open Partner with us and submit a clearly SAMPLE fictional application with an example.test address. Show received-for-review status; it is not brand activation.
8. Optional operator/API demonstration: run `scripts/test-local-demo.ps1` in a separate shell. It uses only generated local test accounts and verifies admin approval -> inactive brand -> activation -> product create/publish, access denial and server cart totals. No admin web console exists.

## Safe reset and evidence

No automatic database deletion/reset command is supplied. Stop only this launcher's backend using Ctrl+C. For a fresh synthetic session, move the stopped `target/launch-demo.mv.db` (and associated H2 trace file if present) plus `target/launch-demo-password.local` into a private dated backup folder under target, then rerun the launcher. Confirm the files are exactly the isolated H2 paths; never move/delete the MySQL volume or another database. Normal restarts preserve demo state. The API smoke script adds timestamped synthetic records each run. Remove the entire ignored verification copy only after stopping its own Vite process, if no longer needed.

See BROWSER-QA-REPORT.md for actual screenshots and tested flows. There is no publicly deployed demo URL. Owner must provide SRS, restore Docker/MySQL, approve the catalogue-only boundary or define transactional scope, and approve hosting before external launch.
