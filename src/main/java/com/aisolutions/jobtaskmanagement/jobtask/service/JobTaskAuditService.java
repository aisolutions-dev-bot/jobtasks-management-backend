package com.aisolutions.jobtaskmanagement.jobtask.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.repository.UserActionLogRepository;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Writes Job Task audit entries on the caller's transaction.
 *
 * Delegates persistence to {@link UserActionLogRepository} through the single
 * {@link #log(SqlClient, JobTaskAuditContext, String, JobTaskAuditChange)} helper so every
 * entry carries the same module, device and timestamp conventions.
 */
@ApplicationScoped
public class JobTaskAuditService {

    private static final String MODULE = "JOBTASKS";

    @Inject
    UserActionLogRepository logRepo;

    /** Identifies who performed the audited action and from which device. */
    public record JobTaskAuditContext(DeviceInfo deviceInfo, String staffCode) {}

    /** Carries the audited reference plus the before and after values. */
    public record JobTaskAuditChange(String jobTaskId, String previousValue, String newValue) {}

    /** Records a reassignment entry describing the previous and new assignee. */
    public Uni<Void> logReassignment(SqlClient client, JobTaskAuditContext context, JobTaskAuditChange change) {
        return log(client, context, "REASSIGN", change);
    }

    /** Records a reschedule entry describing the original and updated due dates. */
    public Uni<Void> logReschedule(SqlClient client, JobTaskAuditContext context, JobTaskAuditChange change) {
        return log(client, context, "RESCHEDULE", change);
    }

    /** Delegates the audit insert and normalizes the created row away. */
    private Uni<Void> log(SqlClient client, JobTaskAuditContext context, String action, JobTaskAuditChange change) {
        String remarks = describe(action, change);
        return logRepo.log(
                        client, context.staffCode(), MODULE, change.jobTaskId(), action, context.deviceInfo(), remarks)
                .replaceWithVoid();
    }

    /** Builds the audit remarks from the action verb and the before and after values. */
    private String describe(String action, JobTaskAuditChange change) {
        String verb = "REASSIGN".equals(action) ? "Reassigned" : "Rescheduled";
        return verb + " from " + change.previousValue() + " to " + change.newValue();
    }
}
