package com.aisolutions.jobtaskmanagement.service.email;

import com.aisolutions.jobtaskmanagement.config.SmtpConfig;
import com.aisolutions.shared.service.email.*;

import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.Context;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Quarkus wrapper for the shared EmailService using injected config.
 */
@ApplicationScoped
public class EmailNotificationService {
    private final EmailService emailService;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    Vertx vertx;

    /**
     * Constructs the service using injected SMTP configuration.
     * The shared EmailService is initialized once at startup.
     */
    public EmailNotificationService(SmtpConfig smtpConfig) {
        EmailConfig sharedConfig = new EmailConfig();
        sharedConfig.setSenderEmail(smtpConfig.senderEmail());
        sharedConfig.setSmtpPassword(smtpConfig.password());

        try {
            this.emailService = new EmailService(sharedConfig, smtpConfig.host(), smtpConfig.port());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize EmailService with provided SMTP configuration", e);
        }
    }

    /**
     * Reactive wrapper around synchronous SMTP send.
     */
    public Uni<Boolean> sendReactive(String to, String subject, String htmlBody) {
        Context context = vertx.getOrCreateContext();

        return Uni.createFrom().item(() -> {
            try {
                emailService.sendEmail(to, subject, htmlBody);
                return true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })
                .runSubscriptionOn(managedExecutor)
                .emitOn(context::runOnContext)
                .onFailure().invoke(Throwable::printStackTrace)
                .onFailure().recoverWithItem(false);
    }
}
