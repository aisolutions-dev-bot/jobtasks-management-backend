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
    // Generates an OpenAPI spec from the JAX-RS annotations below, served at /q/openapi
    // and /q/swagger-ui. The docs site's API reference section is generated from this.
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-reactive-mysql-client")
    implementation("io.quarkus:quarkus-vertx")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-cache")

    // JWT verification against org-api's published JWKS (handles key fetch,
    // caching and rotation; GraalVM native safe)
    implementation("io.quarkus:quarkus-smallrye-jwt")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    // Shared library — 0.1.1 adds the tenancy package (CompanyPoolManager,
    // CompanyDbLookupService, IdentityClaimsExtractor) used for multi-tenant DB routing.
    implementation("com.aisolutions:ai-solutions-java-shared:0.2.2")

    // Email transport used by the shared EmailService (task notification emails)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    implementation("jakarta.activation:jakarta.activation-api:2.1.3")

    // FTP client
    implementation("commons-net:commons-net:3.10.0")

    // Test
    testImplementation(
        enforcedPlatform(
            "${property("quarkusPlatformGroupId")}:${property("quarkusPlatformArtifactId")}:${property("quarkusPlatformVersion")}",
        ),
    )
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
}

group = "com.aisolutions"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
    options.isDeprecation = true
}
