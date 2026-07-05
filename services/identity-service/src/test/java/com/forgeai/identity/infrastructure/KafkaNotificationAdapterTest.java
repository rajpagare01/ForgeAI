package com.forgeai.identity.infrastructure;

import com.forgeai.identity.infrastructure.messaging.KafkaNotificationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class KafkaNotificationAdapterTest extends BaseIntegrationTest {

    @Autowired
    private KafkaNotificationAdapter notificationAdapter;

    @Test
    void testSendNotification() {
        // Simple test to ensure no exceptions are thrown when producing
        notificationAdapter.notify("user-123", "LOGIN", "{\"ip\":\"127.0.0.1\"}");
    }
}
