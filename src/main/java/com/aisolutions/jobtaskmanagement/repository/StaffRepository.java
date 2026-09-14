package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.Staff;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw SqlClient repository for m03Staff.
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in.
 */
@ApplicationScoped
public class StaffRepository {

    private static final String ALL_COLUMNS =
        "Code, StaffId, Name, Department, Appointment, AvatarColor, TelMobile, EmailCompany";

    public Uni<List<Staff>> findAllOrdered(SqlClient client) {
        return client.preparedQuery("SELECT " + ALL_COLUMNS + " FROM m03Staff ORDER BY Name")
            .execute()
            .map(this::mapList);
    }

    /** Find by StaffId varchar (e.g. "T6923") */
    public Uni<Staff> findByStaffId(SqlClient client, String staffId) {
        if (staffId == null) {
            return Uni.createFrom().nullItem();
        }
        return client.preparedQuery("SELECT " + ALL_COLUMNS + " FROM m03Staff WHERE StaffId = ?")
            .execute(Tuple.of(staffId))
            .map(rows -> rows.iterator().hasNext() ? mapRow(rows.iterator().next()) : null);
    }

    private List<Staff> mapList(RowSet<Row> rows) {
        List<Staff> list = new ArrayList<>();
        rows.forEach(row -> list.add(mapRow(row)));
        return list;
    }

    private Staff mapRow(Row row) {
        Staff s = new Staff();
        s.setCode(row.getLong("Code"));
        s.setStaffId(row.getString("StaffId"));
        s.setName(row.getString("Name"));
        s.setDepartment(row.getString("Department"));
        s.setAppointment(row.getString("Appointment"));
        s.setAvatarColor(row.getString("AvatarColor"));
        s.setTelMobile(row.getString("TelMobile"));
        s.setEmailCompany(row.getString("EmailCompany"));
        return s;
    }
}
