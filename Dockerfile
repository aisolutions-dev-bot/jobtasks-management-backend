# Stage 1: Build the JAR

FROM gradle:9.5.0-jdk25 AS builder

WORKDIR /app

# Copy project files

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src/ src/

# Make Gradle wrapper executable

RUN chmod +x ./gradlew

# GitHub credentials
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# Build with environment variables for GitHub Packages
RUN GITHUB_ACTOR=$GITHUB_ACTOR \
  GITHUB_TOKEN=$GITHUB_TOKEN \
  ./gradlew build -Dquarkus.package.jar.type=uber-jar \
  -DquarkusPluginId=$QUARKUS_PLUGIN_ID \
  -DquarkusPluginVersion=$QUARKUS_PLUGIN_VERSION -x test

# Stage 2: Lightweight runtime

FROM eclipse-temurin:25-jre

WORKDIR /app

# Copy the built JAR from builder stage

COPY --from=builder /app/build/jobtasks-management-backend-0.0.1-runner.jar /app/

# Expose Quarkus port

EXPOSE 8090

# Run the application

ENTRYPOINT ["java","-jar","/app/jobtasks-management-backend-0.0.1-runner.jar"]
