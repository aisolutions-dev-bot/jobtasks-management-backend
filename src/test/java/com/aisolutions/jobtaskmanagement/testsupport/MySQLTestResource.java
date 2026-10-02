package com.aisolutions.jobtaskmanagement.testsupport;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts ephemeral MySQL and Kafka containers for one test run.
 *
 * The MySQL container is seeded from {@code schema.sql}, a verbatim capture of the company
 * schema DDL, and its coordinates are published as the default datasource so
 * {@code CompanyPoolManager} routes every repository call to the container instead of the
 * live database.
 */
public class MySQLTestResource implements QuarkusTestResourceLifecycleManager {

    private MySQLContainer<?> mysql;
    private KafkaContainer kafka;

    /** Starts real MySQL and Kafka and enables the production outbox relay for resource tests. */
    @Override
    public Map<String, String> start() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
        kafka.start();
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("jobtasks_management_test")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("schema.sql")
                // The legacy staff table has 100+ varchar columns; InnoDB's strict-mode
                // worst-case row-size check rejects the CREATE TABLE even though the DYNAMIC
                // row format stores the actual data off-page fine.
                .withCommand("--innodb-strict-mode=OFF");
        mysql.start();

        String reactiveUrl =
                String.format("mysql://%s:%d/%s", mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName());

        return Map.of(
                "quarkus.datasource.reactive.url", reactiveUrl,
                "quarkus.datasource.username", mysql.getUsername(),
                "quarkus.datasource.password", mysql.getPassword(),
                "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "notification.outbox.enabled", "true");
    }

    /** Stops both isolated infrastructure containers after the application tests finish. */
    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }
}
