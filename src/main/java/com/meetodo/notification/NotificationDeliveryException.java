package com.meetodo.notification;

public class NotificationDeliveryException extends RuntimeException {
    private final String code;

    public NotificationDeliveryException(String code) {
        super("Notification delivery failed");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
