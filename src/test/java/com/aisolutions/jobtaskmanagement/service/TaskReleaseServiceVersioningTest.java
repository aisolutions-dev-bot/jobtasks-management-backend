package com.aisolutions.jobtaskmanagement.service;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.function.Function;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.client.SystemParameterClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.CreateTaskReleaseRequest;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.TaskReleaseResponse;
import com.aisolutions.jobtaskmanagement.dto.VersionIncrementResponseDTO;
import com.aisolutions.jobtaskmanagement.entity.TaskRelease;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.TaskReleaseRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for Task Release versioning, with repositories and clients mocked out. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskReleaseServiceVersioningTest {

    private static final String GROUP_AUTHORITY = "GROUP-A";

    @Mock
    GroupAuthorityAccessClient accessClient;

    @Mock
    SystemParameterClient systemParameterClient;

    @Mock
    TaskReleaseRepository releaseRepo;

    @Mock
    JobTaskRepository taskRepo;

    @Mock
    CompanyPoolManager companyPoolManager;

    @Mock
    AccessControlService accessControlService;

    @Mock
    Pool pool;

    @Mock
    SqlConnection client;

    @InjectMocks
    TaskReleaseService service;

    @BeforeEach
    void setUp() {
        when(accessControlService.getCurrentCompanyId()).thenReturn("db_test2");
        when(companyPoolManager.poolFor(anyString()))
                .thenReturn(Uni.createFrom().item(pool));
        doAnswer(invocation -> {
                    Function<SqlConnection, Uni<?>> work = invocation.getArgument(0);
                    return work.apply(client);
                })
                .when(pool)
                .withTransaction(any());
        when(accessClient.getAccessByModule(any(), any()))
                .thenReturn(Uni.createFrom().item(List.of(accessGrant("a2402.01"))));
    }

    @Test
    void create_storesVersionReturnedByOrgApiAndServerGeneratedReleaseId() {
        when(systemParameterClient.incrementVersion(any()))
                .thenReturn(Uni.createFrom().item(versionResponse("1.4.8")));
        when(releaseRepo.findByReleaseIdPrefix(any(), anyString()))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(releaseRepo.insert(any(), any())).thenAnswer(invocation -> {
            TaskRelease release = invocation.getArgument(1);
            release.setUniqId(42L);
            return Uni.createFrom().item(release);
        });
        when(releaseRepo.countByReleaseId(any(), anyString()))
                .thenReturn(Uni.createFrom().item(0L));

        TaskReleaseResponse result =
                service.create(GROUP_AUTHORITY, createRequest()).await().indefinitely();

        assertEquals("1.4.8", result.getReleaseVersion());
        assertEquals("PATCH", result.getReleaseType());
        assertEquals("REL-" + Year.now().getValue() + "-001", result.getReleaseId());
    }

    @Test
    void create_whenVersionIncrementFails_propagatesFailureAndCreatesNoRelease() {
        when(systemParameterClient.incrementVersion(any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("org-api unreachable")));

        assertThrows(
                IllegalStateException.class,
                () -> service.create(GROUP_AUTHORITY, createRequest()).await().indefinitely());
        verify(releaseRepo, never()).insert(any(), any());
    }

    @Test
    void create_withoutAddAccess_isForbiddenBeforeAnyWrite() {
        when(accessClient.getAccessByModule(any(), any()))
                .thenReturn(Uni.createFrom().item(List.of(accessGrant("a2402"))));

        assertThrows(
                jakarta.ws.rs.ForbiddenException.class,
                () -> service.create(GROUP_AUTHORITY, createRequest()).await().indefinitely());
        verify(releaseRepo, never()).insert(any(), any());
    }

    @Test
    void previewNextReleaseId_continuesSequenceFromExistingPrefix() {
        TaskRelease existing = new TaskRelease();
        existing.setReleaseId("REL-" + Year.now().getValue() + "-007");
        when(releaseRepo.findByReleaseIdPrefix(any(), anyString()))
                .thenReturn(Uni.createFrom().item(List.of(existing)));

        String nextReleaseId = service.previewNextReleaseId(GROUP_AUTHORITY)
                .await()
                .indefinitely()
                .getReleaseId();

        assertEquals("REL-" + Year.now().getValue() + "-008", nextReleaseId);
        assertTrue(nextReleaseId.matches("REL-\\d{4}-\\d{3}"));
    }

    /** Builds a PATCH release request with no attached job tasks. */
    private static CreateTaskReleaseRequest createRequest() {
        CreateTaskReleaseRequest request = new CreateTaskReleaseRequest();
        request.setReleaseDate(LocalDate.now());
        request.setReleaseType("PATCH");
        request.setJobTaskIds(List.of());
        return request;
    }

    /** Builds the org-api version increment response carrying the next version number. */
    private static VersionIncrementResponseDTO versionResponse(String versionNumber) {
        VersionIncrementResponseDTO response = new VersionIncrementResponseDTO();
        response.setVersionNumber(versionNumber);
        return response;
    }

    /** Builds a granted access entry for the given access code. */
    private static GroupAuthorityAccessDTO accessGrant(String accessCode) {
        GroupAuthorityAccessDTO access = new GroupAuthorityAccessDTO();
        access.setAccessCode(accessCode);
        access.setAccessValue(Boolean.TRUE);
        return access;
    }
}
