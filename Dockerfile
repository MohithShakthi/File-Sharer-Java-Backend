############## STAGE 1: BUILD ##############
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven files first (better caching)
COPY pom.xml .

# Fetch dependencies (much faster than go-offline)
RUN mvn -q dependency:resolve
RUN mvn -q dependency:resolve-plugins

# Copy source code
COPY src ./src

# Build the JAR
RUN mvn -q clean package -DskipTests


############## STAGE 2: RUNTIME ##############
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
