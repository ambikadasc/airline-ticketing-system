# Build stage: compile and package the application with the Maven wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached while only sources change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
# chmod: a checkout on Windows may not carry the executable bit into the build context.
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# Flyway migrations are packaged from db/migrations (see the <resources> section of pom.xml).
COPY db db
# Tests are skipped here because they need Docker (Testcontainers); run them with ./mvnw verify.
RUN ./mvnw -q -B -DskipTests package

# Runtime stage: only the JRE and the jar, run as an unprivileged user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/airline-reservation-*.jar app.jar
USER app
EXPOSE 8080
# Reports healthy once Spring Boot's health endpoint answers UP (curl ships with the base image).
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
