# Brandista

Brandista is a multi-brand e-commerce platform initially targeting Pakistani brands. This repository currently contains only the Java backend foundation. The database and e-commerce features have not yet been implemented.

## Technology stack

- Java 21
- Spring Boot 3.5
- Maven with Maven Wrapper
- Spring Web and Spring Boot Actuator
- JUnit 5 and Spring Boot Starter Test

## Prerequisites

Install a JDK 21 distribution and make sure `JAVA_HOME` points to that JDK and `java` is available on your `PATH`. Check the installed version with:

```shell
java -version
```

The Maven Wrapper downloads and runs the configured Maven version, so a separate Maven installation is not required. The first build needs network access to download Maven and project dependencies.

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

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```shell
./mvnw spring-boot:run
```

The application listens on port `8080`. Check its health at <http://localhost:8080/actuator/health>. A healthy response contains `"status":"UP"`.

Stop the running application with `Ctrl+C` in the terminal where it is running.

## Troubleshooting

- If `java -version` does not show Java 21, install JDK 21 and update `JAVA_HOME` and `PATH`.
- If the wrapper cannot download Maven or dependencies, check the network connection or proxy settings and rerun the command.
- If port `8080` is already in use, stop the other process using it and retry.
- Run commands from the repository root so the wrapper can find `pom.xml`.

No database, authentication, or shopping features are included yet. These will be added in separately scoped tasks.
