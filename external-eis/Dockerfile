FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN apk add --no-cache maven \
    && mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /build/target/external-eis-0.0.1-SNAPSHOT.jar /app/eis.jar
EXPOSE 8090
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-jar", "/app/eis.jar"]
