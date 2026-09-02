# =========================
# STAGE 1: Build
# =========================
FROM eclipse-temurin:25-jdk-jammy AS build

RUN apt-get update && apt-get install -y \
    maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests


# =========================
# STAGE 2: Runtime
# =========================
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/jobshunter-mcp-server-1.0.0.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENV TZ=UTC

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]