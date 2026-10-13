FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build --chown=appuser:appuser /workspace/target/brandista-0.0.1-SNAPSHOT.jar /app/brandista.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/brandista.jar"]
