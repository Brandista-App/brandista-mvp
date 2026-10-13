# Brandista

Brandista is a multi-brand e-commerce platform initially targeting Pakistani brands. This repository currently contains only the Java backend foundation. The database and e-commerce features have not yet been implemented.

## Technology stack

- Java 21
- Spring Boot 3.5
- Maven with Maven Wrapper
- Spring Web, Spring Boot Actuator, and Spring JDBC
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

Edit `.env` and replace both placeholder passwords with local development values. The `.env` file is ignored by Git. Do not use production credentials here.

Build and start MySQL and the backend from the repository root:

```shell
docker compose up --build
```

Compose waits for MySQL's health check before starting the backend. The backend is available at <http://localhost:8080>, and its health endpoint is <http://localhost:8080/actuator/health>. The MySQL port is published only on the local machine at `127.0.0.1:3307`. The backend connects to MySQL over the Compose network using the service name `mysql`.

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

This direct run is useful when a MySQL server is already available at the configured URL. For a ready-to-use local database, use Docker Compose above.

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```shell
./mvnw spring-boot:run
```

The application listens on port `8080` by default. Its database URL, username, password, and port can be provided with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `SERVER_PORT`. Check health at <http://localhost:8080/actuator/health>; when MySQL is configured and reachable, a healthy response contains `"status":"UP"`.

Stop the running application with `Ctrl+C` in the terminal where it is running.

## Troubleshooting

- If `java -version` does not show Java 21, install JDK 21 and update `JAVA_HOME` and `PATH`.
- If the wrapper cannot download Maven or dependencies, check the network connection or proxy settings and rerun the command.
- If port `8080` is already in use, stop the other process using it and retry.
- Run commands from the repository root so the wrapper can find `pom.xml`.

Docker Compose provides the local MySQL service and JDBC connectivity only. Database entities, migrations, authentication, and shopping features have not been implemented yet.
