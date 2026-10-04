package com.aisolutions.jobtaskmanagement.e2e;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the notification handoff.
 *
 * Launches the built service process against real MySQL and Kafka containers, stages a
 * committed outbox row, and asserts the running service relays it to the email topic.
 */
@Tag("e2e")
class JobTaskNotificationOutboxE2E {

    private static final String EMAIL_TOPIC = "notifications.email.v1";
    private static final String OUTBOX_TABLE = "jobtasks_notification_outbox";
    private static final String COMPANY_ID = "db_test2";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(45);
    private static final String[] NOTIFICATION_CHANNEL_NAMES = {
        "email-notifications", "sms-notifications", "whatsapp-notifications"
    };

    @Test
    void relaysCommittedOutboxRowToEmailTopic() throws Exception {
        try (MySQLContainer<?> mysql = startMySql();
                KafkaContainer kafka = startKafka()) {
            createEmailTopic(kafka.getBootstrapServers());
            Process service = launchService(mysql, kafka);
            try {
                awaitOutboxTable(mysql);
                String notificationId = UUID.randomUUID().toString();
                insertOutboxRow(mysql, notificationId);
                ConsumerRecord<String, String> record = awaitEmailRecord(kafka.getBootstrapServers(), notificationId);
                assertThat(record.key()).isEqualTo(COMPANY_ID);
                assertThat(record.value()).contains(notificationId).contains("Job Task E2E notification");
            } finally {
                service.destroyForcibly();
            }
        }
    }

    /** Starts an isolated MySQL container seeded from the captured company schema. */
    private MySQLContainer<?> startMySql() {
        MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("jobtasks_e2e")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("schema.sql")
                .withCommand("--innodb-strict-mode=OFF");
        mysql.start();
        return mysql;
    }

    /** Starts an isolated single-node Kafka container. */
    private KafkaContainer startKafka() {
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
        kafka.start();
        return kafka;
    }

    /** Pre-creates the delivery topic so the assertion cannot depend on broker auto-creation. */
    private void createEmailTopic(String bootstrapServers) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(new NewTopic(EMAIL_TOPIC, 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    /** Launches the built runner through buildServiceCommand with isolated dependency coordinates. */
    private Process launchService(MySQLContainer<?> mysql, KafkaContainer kafka) throws IOException {
        String nativeRunnerPath = System.getProperty("jobtasks.e2e.native-runner");
        Path runner = Path.of(nativeRunnerPath == null ? System.getProperty("jobtasks.e2e.runner") : nativeRunnerPath);
        assertThat(new File(runner.toString())).exists();
        ProcessBuilder builder =
                new ProcessBuilder(buildServiceCommand(runner, kafka.getBootstrapServers(), nativeRunnerPath != null));
        builder.redirectOutput(Path.of("build", "e2e-service.log").toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("QUARKUS_PROFILE", "prod");
        builder.environment().put("DB_URL", reactiveUrl(mysql));
        builder.environment().put("DB_USERNAME", mysql.getUsername());
        builder.environment().put("DB_PASSWORD", mysql.getPassword());
        builder.environment().put("KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        builder.environment().put("SERVICE_CLIENT_SECRET", "e2e-secret");
        builder.environment().put("ORG_SERVICE_URL", "http://127.0.0.1:1");
        builder.environment().put("QUARKUS_HTTP_PORT", Integer.toString(freePort()));
        configureNativeKafkaChannels(builder, kafka.getBootstrapServers(), nativeRunnerPath != null);
        return builder.start();
    }

    /** Selects the native executable or JVM runner and supplies broker settings. */
    private List<String> buildServiceCommand(Path runner, String bootstrapServers, boolean nativeRunner) {
        return nativeRunner ? List.of(runner.toString()) : buildJavaCommand(runner, bootstrapServers);
    }

    /** Builds the Java command and delegates exact broker options to appendChannelBootstrapProperties. */
    private List<String> buildJavaCommand(Path runner, String bootstrapServers) {
        List<String> command = new ArrayList<>();
        command.add("java");
        appendChannelBootstrapProperties(command, bootstrapServers);
        command.add("-jar");
        command.add(runner.toString());
        return command;
    }

    /** Supplies exact Kafka channel bootstrap settings to the native process environment. */
    private void configureNativeKafkaChannels(ProcessBuilder builder, String bootstrapServers, boolean nativeRunner) {
        if (nativeRunner) {
            for (String channelName : NOTIFICATION_CHANNEL_NAMES) {
                builder.environment().put(nativeKafkaBootstrapVariable(channelName), bootstrapServers);
            }
        }
    }

    /** Maps one outgoing channel property to its Quarkus environment variable. */
    private String nativeKafkaBootstrapVariable(String channelName) {
        return "MP_MESSAGING_OUTGOING_" + channelName.replace('-', '_').toUpperCase(Locale.ROOT) + "_BOOTSTRAP_SERVERS";
    }

    /** Adds exact hyphenated SmallRye config keys without environment-name ambiguity. */
    private void appendChannelBootstrapProperties(List<String> command, String bootstrapServers) {
        for (String channelName : NOTIFICATION_CHANNEL_NAMES) {
            command.add("-D" + channelBootstrapServersKey(channelName) + "=" + bootstrapServers);
        }
    }

    /** Builds the exact SmallRye config key that selects one channel's isolated broker. */
    private String channelBootstrapServersKey(String channelName) {
        return "mp.messaging.outgoing." + channelName + ".bootstrap.servers";
    }

    /** Builds the reactive MySQL URL the service expects from its environment. */
    private String reactiveUrl(MySQLContainer<?> mysql) {
        return String.format("mysql://%s:%d/%s", mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName());
    }

    /** Reserves an ephemeral TCP port for the launched service. */
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Waits until the service has provisioned its outbox table, which signals startup completion. */
    private void awaitOutboxTable(MySQLContainer<?> mysql) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (outboxTableExists(mysql)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("The service did not provision its outbox table before the timeout");
    }

    /** Reports whether the outbox table is visible in the container schema. */
    private boolean outboxTableExists(MySQLContainer<?> mysql) {
        try (Connection connection = openConnection(mysql);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, mysql.getDatabaseName());
            statement.setString(2, OUTBOX_TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0;
            }
        } catch (SQLException failure) {
            return false;
        }
    }

    /** Stages a committed delivery request exactly as a business transaction would. */
    private void insertOutboxRow(MySQLContainer<?> mysql, String notificationId) throws SQLException {
        String payload = String.format(
                "{\"notificationId\":\"%s\",\"companyId\":\"%s\",\"recipient\":\"e2e@example.com\","
                        + "\"subject\":\"Job Task E2E\",\"body\":\"Job Task E2E notification body\"}",
                notificationId, COMPANY_ID);
        try (Connection connection = openConnection(mysql);
                PreparedStatement statement = connection.prepareStatement("INSERT INTO " + OUTBOX_TABLE
                        + " (NotificationId, CompanyId, Channel, Payload) VALUES (?,?,?,?)")) {
            statement.setString(1, notificationId);
            statement.setString(2, COMPANY_ID);
            statement.setString(3, "email");
            statement.setString(4, payload);
            statement.executeUpdate();
        }
    }

    /** Consumes the email topic until the staged notification identifier arrives. */
    private ConsumerRecord<String, String> awaitEmailRecord(String bootstrapServers, String notificationId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("group.id", "jobtasks-e2e-" + UUID.randomUUID());
        properties.put("auto.offset.reset", "earliest");
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(EMAIL_TOPIC));
            Instant deadline = Instant.now().plus(RECORD_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(notificationId)) {
                        return record;
                    }
                }
            }
        }
        throw new IllegalStateException("The relay did not publish the staged notification before the timeout");
    }

    /** Opens a JDBC connection to the container for schema checks and staging. */
    private Connection openConnection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                mysql.getUsername(),
                mysql.getPassword());
    }
}
