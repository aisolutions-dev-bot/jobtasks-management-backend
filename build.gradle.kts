plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/AI-Solutions-App/ai-solutions-java-shared")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation(
        enforcedPlatform(
            "${property("quarkusPlatformGroupId")}:${property("quarkusPlatformArtifactId")}:${property("quarkusPlatformVersion")}",
        ),
    )

    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache")
    implementation("io.quarkus:quarkus-reactive-mysql-client")
    implementation("io.quarkus:quarkus-vertx")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    // Shared library
    implementation("com.aisolutions:ai-solutions-java-shared:0.0.3")

    // FTP client
    implementation("commons-net:commons-net:3.10.0")
}

group = "com.aisolutions"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
