package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.UserActionLog;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;

@ApplicationScoped
@WithSession
public class UserActionLogRepository implements PanacheRepositoryBase<UserActionLog, Long> {

    public Uni<UserActionLog> log(String staffId, String module, String referenceNo,
                                  String action, DeviceInfo deviceInfo, String remarks) {
        UserActionLog entry = new UserActionLog();
        entry.setStaffId(staffId);
        entry.setModule(module);
        entry.setReferenceNo(referenceNo);
        entry.setAction(action);
        entry.setLogDate(LocalDateTime.now());
        entry.setDeviceName(deviceInfo != null ? deviceInfo.getDeviceName() : null);
        entry.setDeviceIPAddress(deviceInfo != null ? deviceInfo.getDeviceIPAddress() : null);
        entry.setDeviceSerialNo(deviceInfo != null ? deviceInfo.getDeviceSerialNo() : null);
        entry.setRemarks(remarks);
        return persist(entry);
    }
}
