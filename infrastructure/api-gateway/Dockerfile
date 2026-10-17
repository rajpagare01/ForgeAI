# ------------------------------------------------------------------------------
# Stage 1: Build Application Layer using Maven & Eclipse Temurin 21
# ------------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Cache dependencies by copying POM hierarchy first
COPY pom.xml .
COPY infrastructure/api-gateway/pom.xml infrastructure/api-gateway/
COPY services/identity-service/pom.xml services/identity-service/
COPY services/project-service/pom.xml services/project-service/
COPY services/source-control-service/pom.xml services/source-control-service/
COPY services/testing-service/pom.xml services/testing-service/

# Copy target module sources and compile
COPY infrastructure/api-gateway infrastructure/api-gateway
RUN --mount=type=cache,target=/root/.m2 mvn clean package -Dmaven.test.skip=true -pl infrastructure/api-gateway -am -Dmaven.wagon.http.retryHandler.count=5

# ------------------------------------------------------------------------------
# Stage 2: Extract Spring Boot Layered JAR
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS extractor
WORKDIR /extracted
COPY --from=builder /workspace/infrastructure/api-gateway/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ------------------------------------------------------------------------------
# Stage 3: Minimal Production JRE Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Install curl for health probes and create unprivileged non-root user
RUN apk add --no-cache curl tzdata && \
    addgroup -g 1000 -S forgeai && \
    adduser -u 1000 -S forgeai -G forgeai

# Copy layers in order of change frequency (least frequent to most frequent)
COPY --from=extractor --chown=forgeai:forgeai /extracted/dependencies/ ./
COPY --from=extractor --chown=forgeai:forgeai /extracted/spring-boot-loader/ ./
COPY --from=extractor --chown=forgeai:forgeai /extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=forgeai:forgeai /extracted/application/ ./

USER forgeai:forgeai

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
