FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN sh gradlew --no-daemon :itsm-mock:bootJar \
    && find itsm-mock/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 mock \
    && useradd --uid 10001 --gid mock --create-home mock \
    && install -d -o mock -g mock /var/lib/itsm-mock \
    && install -o mock -g mock /dev/null /var/lib/itsm-mock/.keep
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER mock
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
