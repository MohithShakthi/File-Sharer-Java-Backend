############## STAGE 1: BUILD ##############
FROM maven:3.9.6-eclipse-temurin-17 AS build

# Set the working directory inside container
WORKDIR /app

# Copy only pom.xml first (layer caching)
COPY pom.xml .

# Download dependencies
RUN mvn -q dependency:go-offline

# Copy full project
COPY src ./src

# Build the JAR (skip tests for faster build)
RUN mvn -q clean package -DskipTests


############## STAGE 2: RUN ##############
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose backend port
EXPOSE 8080

# Start the Java application
ENTRYPOINT ["java", "-jar", "app.jar"]
