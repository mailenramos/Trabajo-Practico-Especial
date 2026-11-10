package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.msvc.pagos.enums.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestFingerprintServiceTest {

    private final RequestFingerprintService fingerprints =
            new RequestFingerprintService(new ObjectMapper());

    @Test
    void equivalentPaymentAmountsProduceTheSameFingerprint() {
        UUID purchaseId = UUID.fromString("c9d4c779-11f4-4a2d-beb3-c566657d1146");

        assertEquals(
                fingerprints.paymentFingerprint(purchaseId, new BigDecimal("50000.0"), PaymentMethod.CREDIT_CARD),
                fingerprints.paymentFingerprint(purchaseId, new BigDecimal("50000.00"), PaymentMethod.CREDIT_CARD));
    }

    @Test
    void equivalentRefundAmountsProduceTheSameFingerprint() {
        UUID paymentId = UUID.fromString("c9d4c779-11f4-4a2d-beb3-c566657d1146");
        UUID refundRequestId = UUID.fromString("f3700e94-287b-4099-9315-65831d729f12");

        assertEquals(
                fingerprints.refundFingerprint(paymentId, refundRequestId, new BigDecimal("50000.0"), "reason"),
                fingerprints.refundFingerprint(paymentId, refundRequestId, new BigDecimal("50000.00"), "reason"));
    }
}
