package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import org.jboss.logging.Logger;

/**
 * Read use cases for Job Tasks.
 *
 * RBAC visibility for {@code GET /api/v1/job-tasks}:
 *   a2401.02 = true  → ALL records
 *   a2401.02 = false AND a2401.01 = true  → same department as the caller
 *   a2401.02 = false AND a2401.01 = false → only tasks where the caller is assignor OR assignee
 *
 * Delegates grant resolution to {@link JobTaskAccessService} and row mapping to
 * {@link JobTaskResponseMapper}.
 */
@ApplicationScoped
public class JobTaskQueryService {

    private static final Logger LOG = Logger.getLogger(JobTaskQueryService.class);

    private static final String ACCESS_VIEW_ALL = "a2401.02";
    private static final String ACCESS_VIEW_DEPT = "a2401.01";

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    @Inject
    JobTaskAccessService accessResolver;

    @Inject
    JobTaskResponseMapper viewAssembler;

    /** Resolves the company-routed pool for the current request. */
    private Uni<Pool> currentPool() {
        return companyPoolManager.poolFor(accessControlService.getCurrentCompanyId());
    }

    /** Returns the assignor/assignee staff dropdown for the caller's company. */
    public Uni<List<StaffSummary>> listStaff() {
        return viewAssembler.listStaff(accessControlService.getCurrentCompanyId());
    }

    /** Lists tasks visible to the caller under the RBAC rules. */
    public Uni<List<JobTaskResponse>> listWithRbac(String groupAuthority, String staffCode) {
        return currentPool().flatMap(pool -> listWithRbac(pool, groupAuthority, staffCode));
    }

    /** Resolves grants and the caller's staff row before selecting and enriching visible tasks. */
    private Uni<List<JobTaskResponse>> listWithRbac(Pool pool, String groupAuthority, String staffCode) {
        Uni<List<GroupAuthorityAccessDTO>> accessUni = accessResolver.resolveAccess(groupAuthority);
        Uni<Staff> staffUni = resolveCurrentStaff(pool, staffCode);
        return accessUni.flatMap(accesses ->
                staffUni.flatMap(staff -> selectVisibleTasks(pool, accesses, staff, staffCode)
                        .flatMap(tasks -> viewAssembler.enrichAll(accessControlService.getCurrentCompanyId(), tasks))));
    }

    /**
     * Looks up the caller's staff row without failing the request when it is absent.
     * The lookup is not cached so department changes are reflected immediately.
     */
    private Uni<Staff> resolveCurrentStaff(Pool pool, String staffCode) {
        if (staffCode == null || staffCode.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        return staffRepo.findByStaffId(pool, staffCode).onFailure().recoverWithNull();
    }

    /** Chooses the query for the caller's RBAC scope, failing closed when identity is unknown. */
    private Uni<List<JobTask>> selectVisibleTasks(
            Pool pool, List<GroupAuthorityAccessDTO> accesses, Staff staff, String staffCode) {
        if (accessResolver.hasAccess(accesses, ACCESS_VIEW_ALL)) {
            return taskRepo.findAllActive(pool);
        }
        if (accessResolver.hasAccess(accesses, ACCESS_VIEW_DEPT) && staff != null && staff.getDepartment() != null) {
            return taskRepo.findByDepartment(pool, staff.getDepartment());
        }
        if (staff != null) {
            return taskRepo.findByStaffId(pool, staff.getStaffId());
        }
        LOG.warnf("listWithRbac: could not resolve staff for staffCode='%s' — returning empty list", staffCode);
        return Uni.createFrom().item(List.of());
    }

    /** Loads one active task and delegates response assembly. */
    public Uni<JobTaskResponse> findById(Long id) {
        return currentPool()
                .flatMap(pool -> taskRepo.findActiveById(pool, id)
                        .onItem()
                        .ifNull()
                        .failWith(() -> new NotFoundException("Task " + id + " not found"))
                        .flatMap(task -> viewAssembler.enrichOne(pool, task)));
    }
}
