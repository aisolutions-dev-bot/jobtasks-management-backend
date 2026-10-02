package com.aisolutions.jobtaskmanagement.jobtask;

import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.testsupport.MySQLTestResource;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.ClaimType;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
    private static final String JOB_TASK_ID = "JT-TEST-0001";
    private static final String STAFF_PATH = "/api/v1/job-tasks/staff";
    private static final String TASKS_PATH = "/api/v1/job-tasks";

    @Inject
    CompanyPoolManager companyPoolManager;

    @BeforeEach
    void seedStaffAndTask() {
        Pool pool = companyPoolManager.poolFor("").await().indefinitely();
        pool.preparedQuery("INSERT IGNORE INTO m01Department (DepartmentId, DepartmentName) VALUES (?,?)")
                .execute(Tuple.of("ACCOUNT", "Account"))
                .await()
                .indefinitely();
        pool.preparedQuery(
                        "INSERT IGNORE INTO m03Staff (StaffId, Name, Department, Appointment, TelMobile, EmailCompany)"
                                + " VALUES (?,?,?,?,?,?)")
                .execute(Tuple.of(
                        STAFF_ID, "Job Task Tester", "ACCOUNT", "Engineer", "60123456789", "jobtask-test@example.com"))
                .await()
                .indefinitely();
        pool.preparedQuery("DELETE FROM m24JobTasks WHERE JobTaskId = ?")
                .execute(Tuple.of(JOB_TASK_ID))
                .await()
                .indefinitely();
        pool.preparedQuery("INSERT INTO m24JobTasks (EntryStaff, JobTaskId, TaskTitle, TaskType,"
                        + " AssignorStaffID, AssigneeStaffID, Priority, JobStatus) VALUES (?,?,?,?,?,?,?,?)")
                .execute(Tuple.tuple()
                        .addString(STAFF_ID)
                        .addString(JOB_TASK_ID)
                        .addString("Integration task")
                        .addString("General")
                        .addString(STAFF_ID)
                        .addString(STAFF_ID)
                        .addString("Medium")
                        .addString("Pending"))
                .await()
                .indefinitely();
    }

    @Test
    void listStaff_returnsSeededStaffDirectory() {
        given().when().get(STAFF_PATH).then().statusCode(200).body("staffId", org.hamcrest.Matchers.hasItem(STAFF_ID));
    }

    @Test
    void list_returnsTasksVisibleToTheCallingStaff() {
        given().when()
                .get(TASKS_PATH)
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].jobTaskId", equalTo(JOB_TASK_ID));
    }

    @Test
    void getById_whenTaskDoesNotExist_returnsNotFound() {
        given().when()
                .get(TASKS_PATH + "/999999")
                .then()
                .statusCode(404)
                .body("error", equalTo("Task 999999 not found"));
    }
}
