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

    /** True if another release (different uniqId) already uses this releaseId. */
    public Uni<Boolean> existsByReleaseIdExcluding(String releaseId, Long excludeUniqId) {
        return count("releaseId = ?1 AND uniqId != ?2", releaseId, excludeUniqId)
                .map(c -> c > 0);
    }
}
