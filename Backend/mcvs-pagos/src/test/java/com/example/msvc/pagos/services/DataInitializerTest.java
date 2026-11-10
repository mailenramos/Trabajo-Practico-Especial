package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.example.msvc.pagos.repositories.RefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;

class DataInitializerTest {

    @Test
    void seedsFixedRecordsWithFingerprintsWhenBothTablesAreEmpty() throws Exception {
        PaymentRepository payments = Mockito.mock(PaymentRepository.class);
        RefundRepository refunds = Mockito.mock(RefundRepository.class);
        RequestFingerprintService fingerprints = new RequestFingerprintService(new ObjectMapper());
        when(payments.count()).thenReturn(0L);
        when(refunds.count()).thenReturn(0L);
        AtomicReference<List<Payment>> seededPayments = new AtomicReference<>();
        when(payments.saveAll(anyList())).thenAnswer(invocation -> {
            List<Payment> saved = invocation.getArgument(0);
            seededPayments.set(saved);
            return saved;
        });
        AtomicReference<List<Refund>> seededRefunds = new AtomicReference<>();
        when(refunds.saveAll(anyList())).thenAnswer(invocation -> {
            List<Refund> saved = invocation.getArgument(0);
            seededRefunds.set(saved);
            return saved;
        });

        new DataInitializer(payments, refunds, fingerprints).run(new DefaultApplicationArguments());

        verify(payments).saveAll(anyList());
        assertEquals(2, seededPayments.get().size());
        Payment approved = seededPayments.get().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.APROBADO)
                .findFirst()
                .orElseThrow();
        assertNotNull(approved.getId());
        assertEquals("seed-payment-approved", approved.getIdempotencyKey());
        assertEquals(
                fingerprints.paymentFingerprint(
                        approved.getPurchaseId(), approved.getAmount(), PaymentMethod.CREDIT_CARD),
                approved.getRequestFingerprint());

        assertEquals(2, seededRefunds.get().size());
        verify(refunds).flush();
        Refund refund = seededRefunds.get().stream()
                .filter(item -> item.getStatus() == com.example.msvc.pagos.enums.RefundStatus.EXITOSO)
                .findFirst()
                .orElseThrow();
        assertEquals(approved.getId(), refund.getPayment().getId());
        assertEquals(
                fingerprints.refundFingerprint(
                        approved.getId(),
                        refund.getRefundRequestId(),
                        refund.getAmount(),
                        refund.getReason()),
                refund.getRequestFingerprint());
    }

    @Test
    void doesNotSeedIfEitherTableAlreadyContainsData() throws Exception {
        PaymentRepository payments = Mockito.mock(PaymentRepository.class);
        RefundRepository refunds = Mockito.mock(RefundRepository.class);
        when(payments.count()).thenReturn(1L);
        when(refunds.count()).thenReturn(0L);

        new DataInitializer(
                payments, refunds, new RequestFingerprintService(new ObjectMapper()))
                .run(new DefaultApplicationArguments());

        verify(payments, never()).saveAll(any());
        verify(refunds, never()).saveAll(anyList());
    }
}
