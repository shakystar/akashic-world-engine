FROM gradle:9.1.0-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 akashic
COPY --from=build /app/build/install/akashic-world-engine /app
USER akashic
ENV APP_ENV=production AUTH_MODE=oidc HOST=0.0.0.0 PORT=8765
EXPOSE 8765
ENTRYPOINT ["/app/bin/akashic-world-engine"]
