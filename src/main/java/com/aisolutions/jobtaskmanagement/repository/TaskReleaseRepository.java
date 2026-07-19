package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.TaskRelease;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Reactive Panache repository for m24TaskRelease.
 */
@ApplicationScoped
public class TaskReleaseRepository implements PanacheRepositoryBase<TaskRelease, Long> {

    public Uni<List<TaskRelease>> findAllOrdered() {
        return list("ORDER BY releaseDate DESC");
    }

    public Uni<Long> countByReleaseId(String releaseId) {
        return count("releaseId = ?1", releaseId);
    }
}
