# syntax=docker/dockerfile:1

# ---- Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests && cp target/ultra-sms-gateway-*.jar app.jar

# ---- Run ----
FROM eclipse-temurin:21-jre
RUN groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
