package com.aisolutions.jobtaskmanagement.jobtask;

import java.util.Map;

import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.testsupport.MySQLTestResource;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.ClaimType;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration coverage of {@link JobTaskResource} against real MySQL and Kafka containers.
 *
 * Exercises resource to service to repository to SQL exactly as production runs it, minus the
 * live database; see {@link MySQLTestResource} for the container wiring.
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource.class)
@TestSecurity(user = "JTSTAFF1")
@JwtSecurity(
        claims = {
            @Claim(key = "staffId", value = "JTSTAFF1"),
            @Claim(key = "authorities", value = "[\"TESTGROUP\"]", type = ClaimType.JSON_ARRAY)
        })
class JobTaskResourceTest {

    private static final String STAFF_ID = "JTSTAFF1";
    private static final String SECOND_STAFF_ID = "JTSTAFF2";
    private static final String JOB_TASK_ID = "JT-TEST-0001";
    private static final String TASK_TYPE_GENERAL = "General";
    private static final String STATUS_PENDING = "Pending";
    private static final String STATUS_IN_PROGRESS = "In Progress";
    private static final String STATUS_COMPLETED = "Completed";
    private static final String FIELD_TASK_TITLE = "taskTitle";
    private static final String FIELD_JOB_STATUS = "jobStatus";
    private static final String FIELD_LAST_EDIT_STAFF = "lastEditStaff";
    private static final String STAFF_PATH = "/api/v1/job-tasks/staff";
    private static final String TASKS_PATH = "/api/v1/job-tasks";
    private static final String STAFF_INSERT_COLUMNS =
            " (StaffId, Name, Department, Appointment, TelMobile, EmailCompany) VALUES (?,?,?,?,?,?)";

    @Inject
    CompanyPoolManager companyPoolManager;

    /** Seeds the department, both staff rows, and the integration task for each test. */
    @BeforeEach
    void seedStaffAndTask() {
        Pool pool = currentPool();
        seedAccountDepartment(pool);
        seedStaff(STAFF_ID, "Job Task Tester", "60123456789", "jobtask-test@example.com");
        seedStaff(SECOND_STAFF_ID, "Job Task Reassignee", "60123456780", "jobtask-reassignee@example.com");
        resetIntegrationTasks(pool);
        seedIntegrationTask(pool);
    }

    /** Resolves the connection pool for the empty single-tenant test company. */
    private Pool currentPool() {
        return companyPoolManager.poolFor("").await().indefinitely();
    }

    /** Inserts the account department referenced by the seeded staff rows. */
    private void seedAccountDepartment(Pool pool) {
        pool.preparedQuery("INSERT IGNORE INTO m01Department (DepartmentId, DepartmentName) VALUES (?,?)")
                .execute(Tuple.of("ACCOUNT", "Account"))
                .await()
                .indefinitely();
    }

    /** Inserts one staff row without failing when a previous run already created it. */
    private void seedStaff(String staffId, String name, String mobile, String email) {
        currentPool()
                .preparedQuery("INSERT IGNORE INTO m03Staff" + STAFF_INSERT_COLUMNS)
                .execute(Tuple.of(staffId, name, "ACCOUNT", "Engineer", mobile, email))
                .await()
                .indefinitely();
    }

    /** Removes task rows left by earlier runs so each test starts from a clean task table. */
    private void resetIntegrationTasks(Pool pool) {
        pool.preparedQuery("DELETE FROM m24JobTasks WHERE JobTaskId = ? OR JobTaskId LIKE 'JT-%-%'")
                .execute(Tuple.of(JOB_TASK_ID))
                .await()
                .indefinitely();
    }

    /** Inserts the single pending integration task used by the read and mutation tests. */
    private void seedIntegrationTask(Pool pool) {
        pool.preparedQuery("INSERT INTO m24JobTasks (EntryStaff, JobTaskId, TaskTitle, TaskType,"
                        + " AssignorStaffID, AssigneeStaffID, Priority, JobStatus) VALUES (?,?,?,?,?,?,?,?)")
                .execute(Tuple.tuple()
                        .addString(STAFF_ID)
                        .addString(JOB_TASK_ID)
                        .addString("Integration task")
                        .addString(TASK_TYPE_GENERAL)
                        .addString(STAFF_ID)
                        .addString(STAFF_ID)
                        .addString("Medium")
                        .addString(STATUS_PENDING))
                .await()
                .indefinitely();
    }

    /** Resolves the seeded task's generated primary key. */
    private long seededUniqId() {
        return currentPool()
                .preparedQuery("SELECT UniqID FROM m24JobTasks WHERE JobTaskId = ?")
                .execute(Tuple.of(JOB_TASK_ID))
                .await()
                .indefinitely()
                .iterator()
                .next()
                .getLong("UniqID");
    }

    @Test
    void listStaff_returnsSeededStaffDirectory() {
        given().when().get(STAFF_PATH).then().statusCode(200).body("staffId", hasItem(STAFF_ID));
    }

    @Test
    void list_returnsTasksVisibleToTheCallingStaff() {
        given().when().get(TASKS_PATH).then().statusCode(200).body("jobTaskId", hasItem(JOB_TASK_ID));
    }

    @Test
    void getById_whenTaskExists_returnsTask() {
        given().when()
                .get(TASKS_PATH + "/" + seededUniqId())
                .then()
                .statusCode(200)
                .body("jobTaskId", equalTo(JOB_TASK_ID));
    }

    @Test
    void getById_whenTaskDoesNotExist_returnsNotFound() {
        given().when()
                .get(TASKS_PATH + "/999999")
                .then()
                .statusCode(404)
                .body("error", equalTo("Task 999999 not found"));
    }

    @Test
    void create_persistsTaskAndReturnsGeneratedCode() {
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        FIELD_TASK_TITLE,
                        "Created by integration test",
                        "taskType",
                        TASK_TYPE_GENERAL,
                        "taskDescription",
                        "covers the create path",
                        "assignorStaffId",
                        STAFF_ID,
                        "assigneeStaffId",
                        STAFF_ID,
                        "priority",
                        "High",
                        "dueDate",
                        "2026-12-01",
                        "entryStaff",
                        STAFF_ID))
                .when()
                .post(TASKS_PATH)
                .then()
                .statusCode(201)
                .body("jobTaskId", startsWith("JT-"))
                .body(FIELD_TASK_TITLE, equalTo("Created by integration test"));
    }

    @Test
    void update_changesEditableFields() {
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        FIELD_TASK_TITLE,
                        "Updated title",
                        "taskType",
                        TASK_TYPE_GENERAL,
                        "assigneeStaffId",
                        STAFF_ID,
                        "priority",
                        "Low",
                        "dueDate",
                        "2026-12-15",
                        "remarks",
                        "updated",
                        FIELD_LAST_EDIT_STAFF,
                        STAFF_ID))
                .when()
                .put(TASKS_PATH + "/" + seededUniqId())
                .then()
                .statusCode(200)
                .body(FIELD_TASK_TITLE, equalTo("Updated title"))
                .body("priority", equalTo("Low"));
    }

    @Test
    void updateStatus_toInProgress_setsStartedDate() {
        given().contentType(ContentType.JSON)
                .body(Map.of(FIELD_JOB_STATUS, STATUS_IN_PROGRESS, FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .patch(TASKS_PATH + "/" + seededUniqId() + "/status")
                .then()
                .statusCode(200)
                .body(FIELD_JOB_STATUS, equalTo(STATUS_IN_PROGRESS))
                .body("startedDate", notNullValue());
    }

    @Test
    void updateStatus_toCompleted_queuesCompletionAndSetsCompletedDate() {
        given().contentType(ContentType.JSON)
                .body(Map.of(FIELD_JOB_STATUS, STATUS_COMPLETED, FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .patch(TASKS_PATH + "/" + seededUniqId() + "/status")
                .then()
                .statusCode(200)
                .body(FIELD_JOB_STATUS, equalTo(STATUS_COMPLETED))
                .body("completedDate", notNullValue());
    }

    @Test
    void reassign_updatesAssignee() {
        given().contentType(ContentType.JSON)
                .body(Map.of("newAssigneeStaffId", SECOND_STAFF_ID, FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .patch(TASKS_PATH + "/" + seededUniqId() + "/reassign")
                .then()
                .statusCode(200)
                .body("assignee.staffId", equalTo(SECOND_STAFF_ID));
    }

    @Test
    void reschedule_updatesDueDate() {
        given().contentType(ContentType.JSON)
                .body(Map.of("newDueDate", "2026-11-20", FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .patch(TASKS_PATH + "/" + seededUniqId() + "/reschedule")
                .then()
                .statusCode(200)
                .body("dueDate", notNullValue());
    }

    @Test
    void updateProgressRemarks_storesRemarks() {
        given().contentType(ContentType.JSON)
                .body(Map.of("progressRemarks", "halfway there", FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .patch(TASKS_PATH + "/" + seededUniqId() + "/progress-remarks")
                .then()
                .statusCode(200)
                .body("progressRemarks", equalTo("halfway there"));
    }

    @Test
    void delete_softDeletesTask() {
        given().contentType(ContentType.JSON)
                .body(Map.of(FIELD_LAST_EDIT_STAFF, STAFF_ID))
                .when()
                .delete(TASKS_PATH + "/" + seededUniqId())
                .then()
                .statusCode(204);
    }
}
