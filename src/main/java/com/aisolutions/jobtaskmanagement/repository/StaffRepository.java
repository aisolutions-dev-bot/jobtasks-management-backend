package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.Staff;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class StaffRepository implements PanacheRepositoryBase<Staff, Long> {

    public Uni<List<Staff>> findAllOrdered() {
        return list("ORDER BY name");
    }

    /** Find by StaffId varchar (e.g. "T6923") */
    public Uni<Staff> findByStaffId(String staffId) {
        return find("staffId", staffId).firstResult();
    }
}
