package com.aisolutions.jobtaskmanagement.service.whatsapp;

import com.aisolutions.shared.service.whatsapp.MetaWhatsappProperties;
import com.aisolutions.shared.service.whatsapp.MetaWhatsappService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public class MetaWhatsappServiceProducer {

    @Produces
    @ApplicationScoped
    public MetaWhatsappService metaWhatsappService(
            @ConfigProperty(name = "whatsapp.meta.phone-number-id") String phoneNumberId,
            @ConfigProperty(name = "whatsapp.meta.access-token") String accessToken,
            @ConfigProperty(name = "whatsapp.meta.api-version", defaultValue = "v25.0") String apiVersion) {

        MetaWhatsappProperties properties = new MetaWhatsappProperties(phoneNumberId, accessToken, apiVersion);
        return new MetaWhatsappService(properties);
    }
}
