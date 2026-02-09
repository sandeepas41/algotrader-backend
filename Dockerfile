# Multi-stage Dockerfile for AlgoTrader Backend
# Stage 1: Maven build with dependency caching
# Stage 2: Minimal JRE runtime image

# --- Build Stage ---
FROM eclipse-temurin:24-jdk AS builder

WORKDIR /build

# Copy Maven wrapper and POM first for dependency caching
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build (skip tests — they run in CI)
COPY src src
RUN ./mvnw package -DskipTests -B && \
    java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted

# --- Runtime Stage ---
FROM eclipse-temurin:24-jre

LABEL maintainer="algotrader" \
      description="AlgoTrader Backend - Spring Boot 4.0.2 + Java 24"

# Create non-root user
RUN groupadd -r algotrader && useradd -r -g algotrader -d /app algotrader

WORKDIR /app

# Copy extracted Spring Boot layers (most stable first for better caching)
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

# Create directories for H2 database and tick recordings
RUN mkdir -p /data/h2 /data/ticks && chown -R algotrader:algotrader /data /app

USER algotrader

# Health check — shallow endpoint
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=30s \
    CMD curl -f http://localhost:${SERVER_PORT:-40002}/actuator/health || exit 1

EXPOSE ${SERVER_PORT:-40002}

# JVM tuning for containers: respect cgroup memory limits
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar application.jar"]
