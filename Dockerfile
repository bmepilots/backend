# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src src

RUN ./mvnw -B -DskipTests package \
    && jar_file="$(find target -maxdepth 1 -type f -name 'portal-*.jar' ! -name '*.original' -print -quit)" \
    && test -n "$jar_file" \
    && cp "$jar_file" /tmp/portal.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 portal \
    && useradd --system --uid 10001 --gid portal --home-dir /app --shell /usr/sbin/nologin portal \
    && mkdir -p /app/storage/documents /app/storage/attachments /var/log/bmepilots \
    && chown -R portal:portal /app /var/log/bmepilots

COPY --from=build /tmp/portal.jar /app/portal.jar
COPY docker-entrypoint.sh /usr/local/bin/portal-entrypoint

RUN chmod 0755 /usr/local/bin/portal-entrypoint \
    && chown portal:portal /app/portal.jar

USER portal

ENV JAVA_OPTS=""
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["/usr/local/bin/portal-entrypoint"]
