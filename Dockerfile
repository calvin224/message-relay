FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

RUN chmod +x mvnw

COPY src src

RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests


FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build \
    /app/target/message-relay-1.0.0-SNAPSHOT.jar \
    app.jar

EXPOSE 9000

ENTRYPOINT ["java", "-jar", "app.jar"]