# syntax=docker/dockerfile:1

# ---- Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# The Maven repository is a BuildKit cache kept between builds: on an unstable network, a build that
# stops halfway resumes where it stopped instead of downloading everything again. Only what the build
# needs is downloaded, with more retries and longer timeouts than Maven's defaults.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && \
    ./mvnw -B -q -DskipTests \
      -Daether.connector.http.retryHandler.count=6 \
      -Daether.connector.connectTimeout=30000 \
      -Daether.connector.requestTimeout=180000 \
      package && \
    cp target/ultra-sms-gateway-*-exec.jar app.jar

# ---- Run ----
FROM eclipse-temurin:21-jre
RUN groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
