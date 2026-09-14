package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.UserActionLog;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import com.aisolutions.shared.util.DateUtil;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Raw SqlClient repository for m07UserActionLog — shared audit log table.
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in.
 */
@ApplicationScoped
public class UserActionLogRepository {

    public Uni<UserActionLog> log(SqlClient client, String staffId, String module, String referenceNo,
                                  String action, DeviceInfo deviceInfo, String remarks) {
        UserActionLog entry = new UserActionLog();
        entry.setStaffId(staffId);
        entry.setModule(module);
        entry.setReferenceNo(referenceNo);
        entry.setAction(action);
        entry.setLogDate(DateUtil.nowSGT());
        entry.setDeviceName(deviceInfo != null ? deviceInfo.getDeviceName() : null);
        entry.setDeviceIPAddress(deviceInfo != null ? deviceInfo.getDeviceIPAddress() : null);
        entry.setDeviceSerialNo(deviceInfo != null ? deviceInfo.getDeviceSerialNo() : null);
        entry.setRemarks(remarks);

        String sql = "INSERT INTO m07UserActionLog (StaffId, Module, ReferenceNo, Action, LogDate, " +
            "DeviceName, DeviceIPAddress, DeviceSerialNo, Remarks) VALUES (?,?,?,?,?,?,?,?,?)";

        return client.preparedQuery(sql)
            .execute(Tuple.tuple()
                .addValue(entry.getStaffId())
                .addValue(entry.getModule())
                .addValue(entry.getReferenceNo())
                .addValue(entry.getAction())
                .addValue(entry.getLogDate())
                .addValue(entry.getDeviceName())
                .addValue(entry.getDeviceIPAddress())
                .addValue(entry.getDeviceSerialNo())
                .addValue(entry.getRemarks()))
            .map(result -> {
                entry.setUniqId((Long) result.property(MySQLClient.LAST_INSERTED_ID));
                return entry;
            });
    }
}
