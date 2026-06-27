package com.forgeai.identity.application.port;

/**
 * Output port for sending real-time notifications (WebSocket, SSE).
 * Implemented in the infrastructure layer.
 */
public interface NotificationPort {

    /**
     * Sends a notification to the specified user.
     *
     * @param userId  the target user identifier
     * @param type    the notification type
     * @param payload the notification payload
     */
    void notify(String userId, String type, String payload);
}
