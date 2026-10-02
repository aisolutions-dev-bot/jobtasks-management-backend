import java.security.MessageDigest

plugins {
    java
    id("io.quarkus")
    id("com.diffplug.spotless") version "8.10.2"
    checkstyle
    pmd
    jacoco
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

    // Structured JSON console logs (defaults console to JSON once present) — pairs with
    // the shared lib's RequestCorrelationFilter/exception mappers for Grafana/Loki
    implementation("io.quarkus:quarkus-logging-json")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    // Shared library — the tenancy package (CompanyPoolManager, CompanyDbLookupService)
    // and identity package (IdentityClaimsExtractor) power multi-tenant DB routing.
    implementation("com.aisolutions:ai-solutions-java-shared:0.2.8")

    implementation("io.quarkus:quarkus-messaging-kafka")
    implementation("io.quarkus:quarkus-scheduler")

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
    testImplementation("io.quarkus:quarkus-test-security-jwt")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.apache.kafka:kafka-clients")
    // Testcontainers' JDBC readiness probe needs a blocking driver; the app only uses the
    // reactive Vert.x MySQL client.
    testRuntimeOnly("com.mysql:mysql-connector-j:9.4.0")
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

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    description = "Runs the built service process against real MySQL and Kafka containers."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("e2e")
    }
    shouldRunAfter(tasks.named("test"))
    dependsOn(tasks.named("quarkusBuild"))
    systemProperty("jobtasks.e2e.runner", layout.buildDirectory.file("quarkus-app/quarkus-run.jar").get().asFile)
}

// Only unchanged legacy Java files remain exempt while conventions are adopted.
val legacyJavaBaseline = file("config/conventions/legacy-java-baseline.tsv")
    .readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .groupBy { line -> line.substringAfter('\t') }
    .mapValues { (_, entries) -> entries.map { line -> line.substringBefore('\t') }.toSet() }
val javaFilesRequiringConventions = files(provider {
    fileTree("src") { include("**/*.java") }.files.filter { sourceFile ->
        val sourcePath = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
        val currentDigest = MessageDigest.getInstance("SHA-256")
            .digest(sourceFile.readBytes()).joinToString("") { "%02x".format(it) }
        currentDigest !in legacyJavaBaseline[sourcePath].orEmpty()
    }
})

spotless {
    java {
        target(javaFilesRequiringConventions)
        palantirJavaFormat("2.97.0")
        removeUnusedImports()
        importOrder("java", "javax", "jakarta", "", "org.acme", "\\#")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "14.3.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

pmd {
    toolVersion = "7.28.0"
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    isIgnoreFailures = false
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// Only the hand-written main and test source sets are narrowed to the baseline
// filter. Quarkus-generated sources keep their plugin wiring, otherwise Gradle
// reports the generated-sources lint tasks as consuming quarkusGenerateCode
// output without a declared dependency and fails the image build.
tasks.withType<Checkstyle>().configureEach {
    if (name == "checkstyleMain" || name == "checkstyleTest") {
        setSource(javaFilesRequiringConventions.filter { it.path.contains("/src/${if (name == "checkstyleTest") "test" else "main"}/") })
    }
}

tasks.withType<Pmd>().configureEach {
    if (name == "pmdMain" || name == "pmdTest") {
        setSource(javaFilesRequiringConventions.filter { it.path.contains("/src/${if (name == "pmdTest") "test" else "main"}/") })
    }
}

tasks.register<Copy>("installGitHooks") {
    from("scripts/pre-commit", "scripts/commit-msg")
    into(".git/hooks")
    doLast {
        file(".git/hooks/pre-commit").setExecutable(true)
        file(".git/hooks/commit-msg").setExecutable(true)
    }
}
