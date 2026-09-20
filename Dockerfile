FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 archguard \
    && useradd --system --uid 10001 --gid archguard --home-dir /nonexistent archguard
COPY --from=build /src/target/archguard-platform-0.3.0-SNAPSHOT.jar /opt/archguard/platform.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/opt/archguard/platform.jar"]
