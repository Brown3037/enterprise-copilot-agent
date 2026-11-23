FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S copilot && adduser -S -G copilot copilot \
    && mkdir -p /app/knowledge-cache \
    && chown -R copilot:copilot /app/knowledge-cache
COPY --from=build /workspace/target/enterprise-copilot-agent-*.jar /app/app.jar
ENV SERVER_ADDRESS=0.0.0.0 \
    KNOWLEDGE_CACHE_DIRECTORY=/app/knowledge-cache
USER copilot
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health/check || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
