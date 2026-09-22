# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. The context is the repository root, so the whole reactor is
# available; only the modules this service needs are copied in.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layer first: only POMs are copied, so Docker reuses the downloaded
# repository until a POM actually changes. Without this split every source edit
# would re-download the world.
COPY pom.xml ./
COPY radar-domain/pom.xml radar-domain/
COPY radar-shared/pom.xml radar-shared/
COPY radar-ingestion/pom.xml radar-ingestion/
COPY radar-api/pom.xml radar-api/
RUN mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 -pl radar-api -am dependency:go-offline -DskipTests

# Source layer: invalidated by code changes, but the layer above survives.
COPY .mvn/ .mvn/
COPY radar-domain/src radar-domain/src
COPY radar-shared/src radar-shared/src
COPY radar-api/src radar-api/src
RUN mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 -pl radar-api -am package -DskipTests -Dspotless.check.skip=true

# ---------------------------------------------------------------------------
# Runtime stage. JRE only, unprivileged user, 256 MB heap as the t3.micro budget
# requires. JAVA_TOOL_OPTIONS is overridable from docker-compose.yml.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S radar && adduser -S -G radar radar
WORKDIR /app
COPY --from=build /workspace/radar-api/target/radar-api-*.jar /app/radar-api.jar
USER radar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
ENTRYPOINT ["java", "-jar", "/app/radar-api.jar"]
