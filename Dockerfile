# --- Stage 1: Build ---
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
# Download dependencies first (layer caching)
RUN mvn dependency:go-offline -B
COPY src ./src
# Build the application (skip tests in Docker build; run separately)
RUN mvn package -DskipTests -B

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
# Run with Virtual Threads enabled (Java 21 Loom)
ENTRYPOINT ["java", "-jar", "app.jar"]
EXPOSE 8080
