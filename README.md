# Brandista

Brandista is a multi-brand e-commerce platform initially targeting Pakistani brands. This repository currently contains only the Java backend foundation. The database and e-commerce features have not yet been implemented.

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
