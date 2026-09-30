FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre
ENV SHORTENER_DB_PATH=/data/shortener.sqlite3
WORKDIR /app
COPY --from=build /workspace/target/agentic-sdlc-url-shortener-1.0.0.jar /app/app.jar
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /data && chown -R app:app /app /data
USER app
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
