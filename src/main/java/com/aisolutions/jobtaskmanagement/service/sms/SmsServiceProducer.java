package com.aisolutions.jobtaskmanagement.service.sms;

import com.aisolutions.shared.service.sms.SmsProperties;
import com.aisolutions.shared.service.sms.SmsService;
import io.quarkus.arc.DefaultBean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Singleton
public class SmsServiceProducer {

    @Produces
    @DefaultBean
    public SmsService smsService(
            @ConfigProperty(name = "sms.url") String url,
            @ConfigProperty(name = "sms.username") String user,
            @ConfigProperty(name = "sms.password") String pwd,
            @ConfigProperty(name = "sms.sender-id") String sender) {

        SmsProperties props = new SmsProperties();
        props.setUrl(url);
        props.setUsername(user);
        props.setPassword(pwd);
        props.setSenderId(sender);
        return new SmsService(props);
    }
}
