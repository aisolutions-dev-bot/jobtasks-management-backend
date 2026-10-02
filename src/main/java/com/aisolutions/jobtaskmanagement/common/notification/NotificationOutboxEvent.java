package com.aisolutions.jobtaskmanagement.common.notification;

/** Associates a delivery channel with its immutable notification envelope. */
public record NotificationOutboxEvent(String channel, NotificationEnvelope envelope) {}
