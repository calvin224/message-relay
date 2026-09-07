FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

RUN chmod +x mvnw

COPY src src

RUN ./mvnw -B clean package -DskipTests


FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/target/message-relay-1.0.0-SNAPSHOT.jar app.jar

RUN mkdir -p /data

ENV RELAY_DB_PATH=/data/message-relay.db

VOLUME ["/data"]

EXPOSE 9000

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
