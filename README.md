# Brandista

Brandista is a multi-brand e-commerce platform initially targeting Pakistani brands. This repository contains the Java backend foundation, account registration and authentication, authorization baseline, and brand, category, and initial product management. Cart and commerce features remain future work.

## Technology stack

- Java 21
- Spring Boot 3.5
- Maven with Maven Wrapper
- Spring Web, Spring Boot Actuator, Spring Data JPA, Flyway, Bean Validation, Spring Security, BCrypt password hashing, and Nimbus JWT bearer-token support
- MySQL 8.4 for local development
- JUnit 5 and Spring Boot Starter Test

## Prerequisites

Install a JDK 21 distribution and make sure `JAVA_HOME` points to that JDK and `java` is available on your `PATH`. Check the installed version with:

```shell
java -version
```

The Maven Wrapper downloads and runs the configured Maven version, so a separate Maven installation is not required. The first build needs network access to download Maven and project dependencies.

## Local development with Docker Compose

Install Docker Desktop on Windows or macOS, or Docker Engine with the Docker Compose plugin on Linux. Start Docker and check that both commands work:

```shell
docker --version
docker compose version
```

Create your local environment file from the example. On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

On macOS or Linux:

```shell
cp .env.example .env
```

Edit `.env` and replace both placeholder passwords with local development values. The `.env` file is ignored by Git. Do not use production credentials here. Spring datasource settings are passed as environment variables; Compose points the backend at the MySQL service hostname `mysql`.

Also replace `JWT_SECRET_BASE64` with a fresh, random Base64-encoded key of at least 32 bytes. For example, generate one in PowerShell with:

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Keep that value in your ignored `.env` file or a secret manager; never commit it. The application refuses to start if the key is missing, invalid, or shorter than 32 decoded bytes. JWT issuer, audience, and access-token lifetime can also be configured in `.env`; the local default lifetime is 15 minutes.

Build and start MySQL and the backend from the repository root:

```shell
docker compose up --build
```

Compose waits for MySQL's health check before starting the backend. Flyway runs versioned SQL migrations from `src/main/resources/db/migration` at application startup. The initial `V1` migration creates the User table; add a new versioned migration for every later schema change and never change the meaning of an applied migration. Hibernate uses `ddl-auto=validate`, so it checks mapped entities against the database without creating or updating tables. The backend is available at <http://localhost:8080>, and its health endpoint is <http://localhost:8080/actuator/health>. The MySQL port is published only on the local machine at `127.0.0.1:3307`. The backend connects to MySQL over the Compose network using the service name `mysql`.

Useful commands:

```shell
docker compose logs -f backend mysql
docker compose ps
docker compose build --no-cache backend
```

Use `docker compose up --build` again after rebuilding to start the updated services. Press `Ctrl+C` to stop the foreground process, or run `docker compose down` in another terminal. Both stop the containers while preserving the `mysql_data` volume, so database data survives a normal stop and restart.

`docker compose down -v` removes that volume and permanently deletes the local database data. Do not use it unless you intend to reset the database. MySQL applies the initial database name and passwords only when creating an empty data directory; changing `.env` later does not change credentials stored in an existing volume.

If startup fails, check `docker compose ps` and `docker compose logs mysql backend`. A port conflict can be resolved by changing `BACKEND_PORT` or `MYSQL_PORT` in `.env`. If the backend health response is `DOWN`, confirm that MySQL is healthy and that the database credentials in `.env` match the existing database volume.

## Run tests

From the repository root, run on Windows:

```powershell
.\mvnw.cmd test
```

On macOS or Linux:

```shell
./mvnw test
```

## Start the application

This direct run is useful when a MySQL server is already available. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` in the process environment first. For a ready-to-use local database, use Docker Compose above.

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```shell
./mvnw spring-boot:run
```

The application listens on port `8080` by default. Its database URL, username, password, and port are provided with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `SERVER_PORT`. Check health at <http://localhost:8080/actuator/health>; when MySQL is configured and reachable, a healthy response contains `"status":"UP"`.

Stop the running application with `Ctrl+C` in the terminal where it is running.

## Troubleshooting

- If `java -version` does not show Java 21, install JDK 21 and update `JAVA_HOME` and `PATH`.
- If the wrapper cannot download Maven or dependencies, check the network connection or proxy settings and rerun the command.
- If port `8080` is already in use, stop the other process using it and retry.
- Run commands from the repository root so the wrapper can find `pom.xml`.

Flyway manages database schema changes. The initial `User` persistence model, customer registration, and email/password login are implemented. Marketplace entities and user-management endpoints have not been implemented yet.

## User account policy (proposal)

No SRS or account-policy document is present in this repository, so whether phone login is required remains unresolved. The initial persistence model proposes email-first accounts with a normalized, unique email address. Phone login and phone verification are not part of this model; revisit this proposal when the SRS decision is available.

## Customer registration API

The current email-first endpoint uses the proposed identifier from the account policy above; confirm it against the SRS when that decision is available. No phone or email verification integration is included.

`POST /api/v1/registrations` accepts an email and password. The server trims and lowercases email addresses before validation, checking uniqueness, and persistence. Public requests do not accept a role; every new account is created with the `CUSTOMER` role and `ENABLED` status.

Passwords must contain at least 12 Unicode characters and at most 72 UTF-8 bytes (the BCrypt input limit). They are hashed with Spring Security's BCrypt encoder before persistence. Passwords and hashes are never included in the registration response or application request logs.

Successful registration returns `201 Created` with only the new account ID, normalized email, and creation timestamp. Invalid input returns `400 Bad Request`; a duplicate email returns `409 Conflict` with a generic message that does not include the submitted email or password.

Test the endpoint while the local Compose services are running:

```powershell
$body = @{ email = "customer@example.com"; password = "replace-with-a-long-local-test-password" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/registrations -ContentType "application/json" -Body $body
```

Registration does not yet have rate limiting. Add and verify rate limiting before exposing this endpoint in a pre-production or production environment.

## Login and bearer-token authentication

`POST /api/v1/auth/login` accepts the normalized email identifier and password. For example:

```powershell
$body = @{ email = "customer@example.com"; password = "your-account-password" } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType "application/json" -Body $body
$login | Select-Object tokenType, expiresIn, account
```

The successful response contains an `accessToken`, `tokenType` (`Bearer`), `expiresIn` seconds, and safe account fields (`id`, `email`, and `role`). Send the access token to protected API routes using `Authorization: Bearer <accessToken>`. Invalid credentials and disabled accounts return the same generic `401 Unauthorized` body. Login does not log request bodies or return passwords or password hashes.

Tokens are signed with HS256 using `JWT_SECRET_BASE64`; validation fixes the allowed algorithm and checks signature, issuer, audience, and expiration. The default access-token lifetime is 15 minutes, configurable with `JWT_ACCESS_TOKEN_TTL` (maximum one hour). A stateless access token can remain usable until it expires, including after a client deletes it at logout. There is no token revocation or refresh-token system yet, so logout currently means discarding the token client-side. Account status changes also do not invalidate a token that was already issued. Consider key rotation, rate limiting for registration and login, and an explicit revocation strategy before production use.

The repository has no SRS authentication decision or web/mobile client guidance. This endpoint therefore follows the existing email-first account proposal; confirm the identifier policy against the SRS before treating it as final.

See [SECURITY.md](SECURITY.md) for the route access policy, CORS configuration, CSRF decision, and remaining security gaps.

## Brand management API

Brand data is managed by Brandista administrators. The public `GET /api/v1/brands` endpoint returns active brands only. Admin endpoints require an `ADMIN` bearer token:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/admin/brands` | Create a brand; new brands start active |
| `GET` | `/api/v1/admin/brands` | List active and inactive brands |
| `GET` | `/api/v1/admin/brands/{id}` | Retrieve a brand by ID |
| `PUT` | `/api/v1/admin/brands/{id}` | Replace metadata and set `ACTIVE` or `INACTIVE` status |
| `PATCH` | `/api/v1/admin/brands/{id}/deactivate` | Deactivate without deleting the row |

Slugs are trimmed, lowercased, and whitespace is converted to single hyphens before uniqueness checks. Names are limited to 150 characters, slugs to 160, descriptions to 2,000, and logo references to 2,048. Duplicate slugs return `409 Conflict`; invalid input returns `400 Bad Request`; missing IDs return `404 Not Found`. Inactive brands remain available to admin reads and are excluded from the public listing. The deactivation endpoint does not physically delete data.

Example create request (replace the token with an administrator's access token):

```powershell
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$body = @{
    name = "Example Atelier"
    slug = "Example Atelier"
    description = "A local clothing label"
    logoReference = "https://cdn.example.test/brands/example.svg"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/admin/brands -Headers $headers -ContentType "application/json" -Body $body
```

The logo reference is metadata only. This task does not implement file upload, image validation, or image storage; the SRS leaves the storage provider open for a later decision.

## Category management API

Categories are flat for the MVP. The SRS treats hierarchy as requiring explicit approval, so there are no parent/child fields or nested-category behaviours. The public `GET /api/v1/categories` endpoint returns active categories only. Admin endpoints require an `ADMIN` bearer token:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/admin/categories` | Create an active category |
| `GET` | `/api/v1/admin/categories?page=0&size=50` | Paginated list of active and inactive categories |
| `GET` | `/api/v1/admin/categories/{id}` | Retrieve a category by ID |
| `PUT` | `/api/v1/admin/categories/{id}` | Replace metadata and set `ACTIVE` or `INACTIVE` status |
| `PATCH` | `/api/v1/admin/categories/{id}/deactivate` | Deactivate without deleting the row |
| `GET` | `/api/v1/categories?page=0&size=50` | Paginated list of active categories for discovery |

List endpoints default to 50 records per page and accept sizes from 1 to 100. Slugs are trimmed, lowercased, and whitespace is converted to single hyphens before uniqueness checks. Names are limited to 150 characters, slugs to 160, and descriptions to 2,000. Duplicate slugs return `409 Conflict`; invalid input returns `400 Bad Request`; missing IDs return `404 Not Found`. Inactive categories remain available to admin reads and are excluded from public discovery.

Category deactivation only changes the category status; it does not delete or alter products. Product/category assignment is not implemented yet. When product selection is added, it must reject inactive categories for new assignments while retaining historical references.

## Product management API

The SRS leaves size/colour variants and their inventory model open (FR-PROD-007). This MVP proposes simple products without variants, SKU fields, or a quantity stock counter. Availability is an explicit `AVAILABLE` or `UNAVAILABLE` value; future cart and checkout operations must revalidate both publication and availability. Product images are omitted because the SRS has not selected an image storage provider; no upload or image storage is implemented.

Products belong to exactly one active brand and one active category. Creation starts in `DRAFT`; publishing requires both related records to be active. Public product reads include only `PUBLISHED` products whose brand and category remain active. Deactivation is a soft, terminal state that retains the database row and its brand/category references. No order tables exist yet; future orders must store immutable product/brand/variant snapshots.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/admin/products` | Create a draft product |
| `GET` | `/api/v1/admin/products?page=0&size=50` | Paginated list of products in every publication state |
| `GET` | `/api/v1/admin/products/{id}` | Retrieve a product by ID |
| `PUT` | `/api/v1/admin/products/{id}` | Update product details, PKR price, availability, and active relationships |
| `PATCH` | `/api/v1/admin/products/{id}/publish` | Publish after checking active brand and category |
| `PATCH` | `/api/v1/admin/products/{id}/unpublish` | Remove from public discovery while retaining the product |
| `PATCH` | `/api/v1/admin/products/{id}/deactivate` | Soft-deactivate without deleting the row |
| `GET` | `/api/v1/products?page=0&size=50` | Paginated public list of eligible published products |
| `GET` | `/api/v1/products/{slug}` | Retrieve an eligible published product by slug |

Every `/api/v1/admin/products/**` route requires an `ADMIN` bearer token. Public reads expose only products with `PUBLISHED` status and active brand/category records. The public response includes product name, slug, description, brand/category IDs and names, PKR price, and availability; it omits publication state and persistence timestamps. Product prices use `DECIMAL(12,2)` and JSON decimal values; accepted prices are non-negative, with at most 10 integer digits and 2 decimal places. Slugs are normalized to lowercase hyphen-separated text before uniqueness checks. Duplicate slugs return `409 Conflict`, invalid request data returns `400 Bad Request`, and missing products or related IDs return `404 Not Found`.

### Public product discovery

`GET /api/v1/products` supports `page` (default `0`), `size` (default `50`, range `1`–`100`), optional `keyword` (up to 100 characters), positive `brandId` and `categoryId`, and `sort` (`default`, `price_asc`, or `price_desc`). The default order is product name ascending, then ID ascending. Price sorts use ID ascending to keep ties stable. Unknown but valid brand/category IDs produce an empty page; invalid values return a safe `400 Bad Request` response.

Search is a case-insensitive substring match against product name and description. `%`, `_`, and `!` in a keyword are escaped and treated literally. Filters combine with the keyword using AND semantics. Only published products with currently active brand and category records are considered.

Example request:

```text
GET /api/v1/products?keyword=linen&brandId=12&categoryId=4&sort=price_asc&page=0&size=20
```

Example response:

```json
{
  "content": [
    {
      "id": 42,
      "name": "Linen Shirt",
      "slug": "linen-shirt",
      "description": "Lightweight summer shirt",
      "brandId": 12,
      "brandName": "Example Atelier",
      "categoryId": 4,
      "categoryName": "Clothing",
      "price": 1299.95,
      "currency": "PKR",
      "availability": "AVAILABLE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`GET /api/v1/products/{slug}` returns the same public product shape and returns `404 Not Found` for missing, unpublished, or ineligible products. Search uses database substring matching; its leading wildcard prevents ordinary B-tree indexes from accelerating keyword matches. The implementation keeps filtering and paging in SQL and adds indexes for publication/price and publication/brand/category query paths. It is intended for MVP catalogue scale; full-text search infrastructure is not included.

The availability flag is not live inventory and does not reserve or decrement stock. Cart, checkout, purchase rejection, inventory reservation, and order-history snapshots are not implemented by this task.

## Customer address management

Authenticated customers manage only their own delivery addresses under `/api/v1/customers/me/addresses`. The API derives the customer ID from the signed JWT subject; clients cannot choose an owner ID. Attempts to read or mutate another customer's address return the same `404 Not Found` response as a missing address.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/customers/me/addresses` | Create an address for the authenticated customer |
| `GET` | `/api/v1/customers/me/addresses` | List the authenticated customer's addresses |
| `GET` | `/api/v1/customers/me/addresses/{addressId}` | Retrieve one owned address |
| `PUT` | `/api/v1/customers/me/addresses/{addressId}` | Replace all editable fields of one owned address |
| `PATCH` | `/api/v1/customers/me/addresses/{addressId}` | Change only supplied fields of one owned address |
| `DELETE` | `/api/v1/customers/me/addresses/{addressId}` | Delete one owned address |

Example create/update body:

```json
{
  "recipientName": "Ayesha Khan",
  "phoneNumber": "+92 300 1234567",
  "addressLine1": "House 12, Street 4",
  "addressLine2": "Gulberg III",
  "city": "Lahore",
  "province": "Punjab",
  "postalCode": "54660"
}
```

Example create response (`201 Created`):

```json
{
  "id": 8,
  "recipientName": "Ayesha Khan",
  "phoneNumber": "+92 300 1234567",
  "addressLine1": "House 12, Street 4",
  "addressLine2": "Gulberg III",
  "city": "Lahore",
  "province": "Punjab",
  "postalCode": "54660",
  "createdAt": "2026-10-14T12:30:00",
  "updatedAt": "2026-10-14T12:30:00"
}
```

The response contains the address ID, those address fields, and creation/update timestamps. Recipient name, phone, address line 1, and city are required; line 2, province, and postal code are optional. `PUT` replaces every editable field. `PATCH` accepts any non-empty subset; send an empty string to clear an optional field. Phone values allow common punctuation and 7–15 digits; this is syntax validation only and does not verify ownership of a phone number. Requests require a `CUSTOMER` bearer token. Validation failures return `400`; missing or non-owned addresses return `404`; unauthenticated requests return `401`.

The SRS calls for address selection and an order-time address snapshot but does not define a default-address rule, so this API has no default flag or default-selection endpoint. Address deletion removes the saved row; future order creation must copy delivery details into an immutable order snapshot rather than depend on this address record. Phone formatting, deliverable-city coverage, and postal-code requirements remain business decisions.

## Customer cart

Authenticated customers manage one persistent cart under `/api/v1/customers/me/cart`. The customer is derived from the signed bearer token; cart and customer IDs are not accepted to select ownership.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/customers/me/cart` | Retrieve the current cart, creating an empty persistent cart if needed |
| `POST` | `/api/v1/customers/me/cart/items` | Add a product and positive integer quantity (`201` for a new item, `200` when increasing an existing item) |
| `PATCH` | `/api/v1/customers/me/cart/items/{itemId}` | Replace an item's quantity |
| `DELETE` | `/api/v1/customers/me/cart/items/{itemId}` | Remove an owned item (`204 No Content`) |
| `DELETE` | `/api/v1/customers/me/cart/items` | Clear this customer's items; returns the empty cart |

Add request:

```json
{ "productId": 42, "quantity": 2 }
```

Cart response (`GET`, add/update, and clear):

```json
{
  "id": 6,
  "items": [
    {
      "itemId": 9,
      "productId": 42,
      "productName": "Linen Shirt",
      "brandName": "Example Atelier",
      "unitPrice": 1299.95,
      "quantity": 2,
      "lineSubtotal": 2599.90,
      "available": true
    }
  ],
  "subtotal": 2599.90,
  "currency": "PKR"
}
```

Cart reads return an empty `items` array and a zero subtotal when there are no items. Add accepts only currently published, available products whose brand and category are active; otherwise it returns `409 Conflict`. If a product becomes ineligible after being added, it remains visible with `available: false` so the customer can remove it. The server computes the displayed line subtotals and cart subtotal from current `BigDecimal` product prices. These are provisional display values, not checkout prices. Checkout must revalidate product eligibility and all prices.

The SRS does not define a maximum item quantity; the API requires a positive 32-bit integer and rejects aggregate-add overflow, without inventing a business cap. Existing B010 uses simple products, with no variant or stock-count model; this cart therefore has no variant selection, stock enforcement, or inventory reservation. Carts can contain products from multiple brands. A `CUSTOMER` bearer token is required; a missing token returns `401`, a non-customer role returns `403`, invalid quantity returns `400`, and missing/non-owned item IDs return `404`. No price or owner field is accepted from the client.
