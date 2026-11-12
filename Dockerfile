FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon && cp build/libs/*.jar /workspace/meetodo.jar

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S -g 10001 meetodo && adduser -S -u 10001 -G meetodo meetodo
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/meetodo.jar ./app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
