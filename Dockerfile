# syntax=docker/dockerfile:1
ARG JAVA_IMAGE=eclipse-temurin:26.0.2.1_1-jdk-noble

FROM ${JAVA_IMAGE} AS build
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    bash ./mvnw -B -pl setu-server -am -DskipTests package && \
    bash ./mvnw -B -N dependency:copy \
        -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.0 \
        -DoutputDirectory=/otel-agent && \
    javac --release 26 -d /readiness-probe docker/ReadinessProbe.java

FROM ${JAVA_IMAGE} AS runtime
WORKDIR /opt/setu
RUN groupadd --gid 10001 setu && \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /opt/setu --shell /usr/sbin/nologin setu && \
    mkdir -p /var/lib/setu /var/log/setu && \
    chown 10001:10001 /var/lib/setu /var/log/setu
COPY --from=build /workspace/setu-server/target/setu-server-*.jar ./setu-server.jar
COPY --from=build /workspace/setu-server/target/lib/ ./lib/
COPY --from=build /otel-agent/opentelemetry-javaagent-2.33.0.jar ./otel/opentelemetry-javaagent.jar
COPY --from=build /readiness-probe/ ./probe/
COPY config/jvm.options config/logback.xml ./config/
COPY config/observability/otel-agent.properties ./config/observability/otel-agent.properties
COPY docker/server.properties /etc/setu/server.properties
COPY LICENSE ./LICENSE
ENV SETU_LOG_DIR=/var/log/setu
ENV JDK_JAVA_OPTIONS="-Xms64m -Xmx128m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2"
LABEL org.opencontainers.image.title="Setu" \
      org.opencontainers.image.description="Single-node Java message broker with partitioned logs and durable acknowledgements" \
      org.opencontainers.image.source="https://github.com/RishabhRathi-Dev/Setu" \
      org.opencontainers.image.licenses="Apache-2.0"
USER 10001:10001
EXPOSE 9092
STOPSIGNAL SIGTERM
# Disable the agent for this separate short-lived JVM, including when JAVA_TOOL_OPTIONS enables it for the broker.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["java", "-Dotel.javaagent.enabled=false", "-Xms8m", "-Xmx32m", "-XX:ActiveProcessorCount=1", "-cp", "/opt/setu/probe", "io.setu.container.ReadinessProbe"]
ENTRYPOINT ["java", "@/opt/setu/config/jvm.options", "-jar", "/opt/setu/setu-server.jar"]
CMD ["--config", "/etc/setu/server.properties"]
