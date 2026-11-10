package com.example.msvc.pagos.entities;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EntityTimestampsTest {

    @Test
    void paymentLifecycleCallbacksSetCreationAndUpdateTimestamps() {
        Payment payment = new Payment();

        ReflectionTestUtils.invokeMethod(payment, "onCreate");
        Instant createdAt = payment.getCreatedAt();
        Instant firstUpdatedAt = payment.getUpdatedAt();

        assertNotNull(createdAt);
        assertTrue(firstUpdatedAt.equals(createdAt));

        ReflectionTestUtils.invokeMethod(payment, "onUpdate");

        assertTrue(!payment.getUpdatedAt().isBefore(firstUpdatedAt));
        assertTrue(payment.getCreatedAt().equals(createdAt));
    }

    @Test
    void refundLifecycleCallbacksSetCreationAndUpdateTimestamps() {
        Refund refund = new Refund();

        ReflectionTestUtils.invokeMethod(refund, "onCreate");
        Instant createdAt = refund.getCreatedAt();
        Instant firstUpdatedAt = refund.getUpdatedAt();

        assertNotNull(createdAt);
        assertTrue(firstUpdatedAt.equals(createdAt));

        ReflectionTestUtils.invokeMethod(refund, "onUpdate");

        assertTrue(!refund.getUpdatedAt().isBefore(firstUpdatedAt));
        assertTrue(refund.getCreatedAt().equals(createdAt));
    }
}
