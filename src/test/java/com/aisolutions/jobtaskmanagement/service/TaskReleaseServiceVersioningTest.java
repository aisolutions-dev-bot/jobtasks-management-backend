package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.client.SystemParameterClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.CreateTaskReleaseRequest;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.TaskReleaseResponse;
import com.aisolutions.jobtaskmanagement.dto.VersionIncrementResponseDTO;
import com.aisolutions.jobtaskmanagement.repository.TaskReleaseRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
class TaskReleaseServiceVersioningTest {

    @Inject
    TaskReleaseService service;

    @Inject
    TaskReleaseRepository releaseRepo;

    @InjectMock
    @RestClient
    SystemParameterClient systemParameterClient;

    @InjectMock
    @RestClient
    GroupAuthorityAccessClient accessClient;

    private static GroupAuthorityAccessDTO accessGrant(String accessCode) {
        GroupAuthorityAccessDTO dto = new GroupAuthorityAccessDTO();
        dto.setAccessCode(accessCode);
        dto.setAccessValue(Boolean.TRUE);
        return dto;
    }

    @Test
    void create_withPatchReleaseType_storesVersionReturnedByOrgApi() throws Throwable {
        when(accessClient.getAccessByModule(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Uni.createFrom().item(List.of(accessGrant("a2402.01"))));

        VersionIncrementResponseDTO versionResponse = new VersionIncrementResponseDTO();
        versionResponse.setVersionNumber("1.4.8");
        when(systemParameterClient.incrementVersion(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Uni.createFrom().item(versionResponse));

        CreateTaskReleaseRequest req = new CreateTaskReleaseRequest();
        req.setReleaseDate(LocalDate.now());
        req.setReleaseType("PATCH");
        req.setJobTaskIds(List.of());

        // TaskReleaseService.create is @WithTransaction, which requires an active
        // Vertx duplicated context to subscribe on; VertxContextSupport.subscribeAndAwait
        // provides that context (a plain JUnit thread does not have one).
        TaskReleaseResponse result = VertxContextSupport.subscribeAndAwait(() -> service.create("GROUP-A", req));

        assertEquals("1.4.8", result.getReleaseVersion());
        assertEquals("PATCH", result.getReleaseType());
        assertTrue(result.getReleaseId().matches("REL-\\d{4}-\\d{3}"),
            "releaseId should be server-generated as REL-YYYY-NNN, was: " + result.getReleaseId());
    }

    @Test
    void create_whenVersionIncrementFails_propagatesFailureAndCreatesNoRelease() throws Throwable {
        when(accessClient.getAccessByModule(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Uni.createFrom().item(List.of(accessGrant("a2402.01"))));
        when(systemParameterClient.incrementVersion(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("org-api unreachable")));

        CreateTaskReleaseRequest req = new CreateTaskReleaseRequest();
        req.setReleaseDate(LocalDate.now());
        req.setReleaseType("PATCH");
        req.setJobTaskIds(List.of());

        Long countBefore = VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> releaseRepo.count()));

        // Assert that the service call fails
        assertThrows(Throwable.class, () ->
            VertxContextSupport.subscribeAndAwait(() -> service.create("GROUP-A", req)));

        // Verify no TaskRelease row was persisted as a side effect of the failed increment call
        Long countAfter = VertxContextSupport.subscribeAndAwait(() -> Panache.withSession(() -> releaseRepo.count()));
        assertEquals(countBefore, countAfter, "No TaskRelease should have been persisted when version increment fails");
    }
}
