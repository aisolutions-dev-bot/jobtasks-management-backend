package com.aisolutions.jobtaskmanagement.service.sms;

import com.aisolutions.shared.service.sms.SmsService;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SmsNotificationService {

    @Inject
    SmsService smsService;

    public long sendSms(String e164Mobile, String text) {
        return smsService.sendSms(e164Mobile, text);
    }

    public Uni<Boolean> sendReactive(String e164Mobile, String text) {
        return Uni.createFrom().item(() -> {
            smsService.sendSms(e164Mobile, text); // blocking call
            return true; // indicate success
        })
                .onFailure().recoverWithItem(false); // mark failure as false instead of throwing
    }
}
