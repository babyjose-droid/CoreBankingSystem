# syntax=docker/dockerfile:1.7
# CoreBanking backend image. Build from the REPOSITORY ROOT:
#   docker build -f backend/app/Dockerfile -t corebanking/backend:dev .

# ---- Stage 1: build the Spring Boot jar ------------------------------------------------------
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
ENV GRADLE_USER_HOME=/cache/gradle

# Build scripts first so dependency resolution is cached separately from source changes.
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY backend/ backend/

RUN --mount=type=cache,target=/cache/gradle \
    gradle :backend:app:bootJar --no-daemon --console=plain \
 && find backend/app/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \; \
 && test -s /workspace/app.jar

# ---- Stage 2: runtime ------------------------------------------------------------------------
FROM eclipse-temurin:24-jre

LABEL org.opencontainers.image.title="corebanking-backend" \
      org.opencontainers.image.description="CoreBanking core banking and lending backend" \
      org.opencontainers.image.source="https://github.com/corebanking/corebanking"

# Fixed non-root uid/gid 10001 (matches runAsUser in the Helm chart). No shell login, no home writes.
RUN groupadd --system --gid 10001 corebanking \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin corebanking \
 && mkdir -p /app \
 && chown root:root /app

WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# Container-aware heap; fail fast on OOM so Kubernetes restarts the pod. /tmp is an emptyDir/tmpfs
# because the root filesystem is read-only. IST is the business timezone for EOD/value dates.
# Extra JVM flags: set JAVA_TOOL_OPTIONS (picked up by the JVM itself; no shell wrapper needed).
ENV TZ=Asia/Kolkata
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
