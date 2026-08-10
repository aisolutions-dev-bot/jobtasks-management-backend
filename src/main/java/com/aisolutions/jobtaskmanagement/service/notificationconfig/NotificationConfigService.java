package com.aisolutions.jobtaskmanagement.service.notificationconfig;

import com.aisolutions.jobtaskmanagement.client.NotificationConfigClient;
import com.aisolutions.jobtaskmanagement.dto.NotificationConfigDTO;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class NotificationConfigService {

    private static final Logger LOG = Logger.getLogger(NotificationConfigService.class);

    @Inject
    @RestClient
    NotificationConfigClient notificationConfigClient;

    private volatile boolean emailEnabled = true;
    private volatile boolean smsEnabled = true;
    private volatile boolean whatsappEnabled = true;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("[NotificationConfig] Loading notification flags from org api...");
        CountDownLatch latch = new CountDownLatch(1);
        notificationConfigClient.getNotificationConfig()
                .subscribe().with(
                        config -> {
                            emailEnabled = config.isEmailEnabled();
                            smsEnabled = config.isSmsEnabled();
                            whatsappEnabled = config.isWhatsappEnabled();
                            LOG.infof("[NotificationConfig] Loaded — email=%s, sms=%s, whatsapp=%s",
                                    emailEnabled, smsEnabled, whatsappEnabled);
                            latch.countDown();
                        },
                        err -> {
                            LOG.errorf(err, "[NotificationConfig] Failed to load flags, defaulting all to true. Error: %s", err.getMessage());
                            latch.countDown();
                        }
                );
        try {
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                LOG.warn("[NotificationConfig] Timed out waiting for org api response, defaulting all to true");
            }
        } catch (InterruptedException e) {
            LOG.warn("[NotificationConfig] Interrupted while waiting for org api response, defaulting all to true");
            Thread.currentThread().interrupt();
        }
    }

    public Uni<NotificationConfigDTO> getConfig() {
        NotificationConfigDTO config = new NotificationConfigDTO();
        config.setEmailEnabled(emailEnabled);
        config.setSmsEnabled(smsEnabled);
        config.setWhatsappEnabled(whatsappEnabled);
        return Uni.createFrom().item(config);
    }

    public Uni<NotificationConfigDTO> refresh() {
        return notificationConfigClient.getNotificationConfig()
                .invoke(config -> {
                    emailEnabled = config.isEmailEnabled();
                    smsEnabled = config.isSmsEnabled();
                    whatsappEnabled = config.isWhatsappEnabled();
                })
                .onFailure().recoverWithItem(() -> getConfig().await().indefinitely());
    }
}
