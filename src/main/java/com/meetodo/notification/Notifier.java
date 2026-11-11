package com.meetodo.notification;

public interface Notifier {
    String type();

    void send(NotificationMessage message);
}
