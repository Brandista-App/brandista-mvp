FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser \
    && (getent group 1000 >/dev/null || groupadd --gid 1000 render-secrets) \
    && usermod -a -G 1000 appuser \
    && command -v openssl >/dev/null
COPY --from=build --chown=appuser:appuser /workspace/target/brandista-0.0.1-SNAPSHOT.jar /app/brandista.jar

COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

USER appuser
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
