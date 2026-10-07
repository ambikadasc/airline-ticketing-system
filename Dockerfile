# Build stage: compile and package the application with the Maven wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached while only sources change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY src src
# Flyway migrations are packaged from db/migrations (see the <resources> section of pom.xml).
COPY db db
# Tests are skipped here because they need Docker (Testcontainers); run them with ./mvnw verify.
RUN ./mvnw -q -B -DskipTests package

# Runtime stage: only the JRE and the jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/airline-reservation-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
