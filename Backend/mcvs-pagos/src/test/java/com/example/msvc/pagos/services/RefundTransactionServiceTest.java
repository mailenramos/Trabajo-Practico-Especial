package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.msvc.pagos.dto.RefundCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.enums.RefundStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.repositories.PaymentIdProjection;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.example.msvc.pagos.repositories.RefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RefundTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;

    private UUID paymentId;
    private UUID refundId;
    private Payment payment;
    private RequestFingerprintService fingerprints;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paymentId = UUID.randomUUID();
        refundId = UUID.randomUUID();
        payment = new Payment();
        payment.setId(paymentId);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus(PaymentStatus.APROBADO);
        fingerprints = new RequestFingerprintService(new ObjectMapper());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByIdempotencyKey("refund-key")).thenReturn(Optional.empty());
        when(refundRepository.findByRefundRequestId(any())).thenReturn(Optional.empty());
        when(refundRepository.sumAmountsByPaymentIdAndStatuses(eq(paymentId), any()))
                .thenReturn(BigDecimal.ZERO);
        when(refundRepository.saveAndFlush(any(Refund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptsRefundWhenTotalExactlyEqualsPaymentAmount() {
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        RefundCreationResult result = service.create(
                paymentId,
                "refund-key",
                new RefundCreateRequest(UUID.randomUUID(), new BigDecimal("100.00"), "full refund"));

        assertEquals(RefundStatus.EXITOSO, result.refund().getStatus());
        assertTrue(!result.replayed());
        verify(refundRepository).sumAmountsByPaymentIdAndStatuses(
                eq(paymentId), eq(List.of(RefundStatus.EXITOSO, RefundStatus.PENDIENTE)));
        InOrder order = inOrder(paymentRepository, refundRepository);
        order.verify(paymentRepository).findByIdForUpdate(paymentId);
        order.verify(refundRepository).findByIdempotencyKey("refund-key");
        order.verify(refundRepository).findByRefundRequestId(any());
    }

    @Test
    void failedRefundDoesNotConsumeBalanceAndCanBeRetried() {
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "FAILURE");
        RefundCreationResult created = service.create(
                paymentId,
                "refund-key",
                new RefundCreateRequest(UUID.randomUUID(), new BigDecimal("100.00"), "refund"));
        Refund failed = created.refund();
        PaymentIdProjection reference = org.mockito.Mockito.mock(PaymentIdProjection.class);
        when(reference.getPaymentId()).thenReturn(paymentId);
        when(refundRepository.findPaymentIdByRefundId(refundId)).thenReturn(Optional.of(reference));
        failed.setId(refundId);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(failed));

        Refund retried = service.retry(refundId);

        assertEquals(RefundStatus.FALLIDO, retried.getStatus());
        assertEquals(2, retried.getAttemptCount());
        InOrder order = inOrder(refundRepository, paymentRepository);
        order.verify(refundRepository).findPaymentIdByRefundId(refundId);
        order.verify(paymentRepository).findByIdForUpdate(paymentId);
        order.verify(refundRepository).findById(refundId);
    }

    @Test
    void rejectsRetryForNonFailedRefund() {
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");
        PaymentIdProjection reference = org.mockito.Mockito.mock(PaymentIdProjection.class);
        when(reference.getPaymentId()).thenReturn(paymentId);
        when(refundRepository.findPaymentIdByRefundId(refundId)).thenReturn(Optional.of(reference));
        Refund succeeded = new Refund();
        succeeded.setStatus(RefundStatus.EXITOSO);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(succeeded));

        assertThrows(ConflictException.class, () -> service.retry(refundId));
        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void successfulRetryResolvesSameRefundAndIncrementsAttemptCount() {
        RefundTransactionService failingService =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "FAILURE");
        Refund failed = failingService.create(
                        paymentId,
                        "refund-key",
                        new RefundCreateRequest(UUID.randomUUID(), new BigDecimal("100.00"), "refund"))
                .refund();
        failed.setId(refundId);
        PaymentIdProjection reference = org.mockito.Mockito.mock(PaymentIdProjection.class);
        when(reference.getPaymentId()).thenReturn(paymentId);
        when(refundRepository.findPaymentIdByRefundId(refundId)).thenReturn(Optional.of(reference));
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(failed));
        RefundTransactionService succeedingService =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        Refund retried = succeedingService.retry(refundId);

        assertEquals(RefundStatus.EXITOSO, retried.getStatus());
        assertEquals(2, retried.getAttemptCount());
        assertEquals(refundId, retried.getId());
    }

    @Test
    void secondRetryAfterSuccessConflictsWithoutChangingRecord() {
        Refund failed = new Refund();
        failed.setId(refundId);
        failed.setPayment(payment);
        failed.setStatus(RefundStatus.FALLIDO);
        failed.setAmount(new BigDecimal("25.00"));
        failed.setAttemptCount(1);
        PaymentIdProjection reference = org.mockito.Mockito.mock(PaymentIdProjection.class);
        when(reference.getPaymentId()).thenReturn(paymentId);
        when(refundRepository.findPaymentIdByRefundId(refundId)).thenReturn(Optional.of(reference));
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(failed));
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        service.retry(refundId);
        int attemptCountAfterSuccess = failed.getAttemptCount();
        assertThrows(ConflictException.class, () -> service.retry(refundId));

        assertEquals(RefundStatus.EXITOSO, failed.getStatus());
        assertEquals(attemptCountAfterSuccess, failed.getAttemptCount());
        verify(refundRepository).saveAndFlush(failed);
    }

    @Test
    void rejectsRetryWhenFailedAmountPlusOtherValidRefundsExceedsPayment() {
        Refund failed = new Refund();
        failed.setId(refundId);
        failed.setPayment(payment);
        failed.setStatus(RefundStatus.FALLIDO);
        failed.setAmount(new BigDecimal("75.00"));
        failed.setAttemptCount(1);
        PaymentIdProjection reference = org.mockito.Mockito.mock(PaymentIdProjection.class);
        when(reference.getPaymentId()).thenReturn(paymentId);
        when(refundRepository.findPaymentIdByRefundId(refundId)).thenReturn(Optional.of(reference));
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(failed));
        when(refundRepository.sumAmountsByPaymentIdAndStatuses(eq(paymentId), any()))
                .thenReturn(new BigDecimal("30.00"));
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        assertThrows(ConflictException.class, () -> service.retry(refundId));

        assertEquals(1, failed.getAttemptCount());
        assertEquals(RefundStatus.FALLIDO, failed.getStatus());
        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCreateWhenRefundsWouldExceedThePayment() {
        when(refundRepository.sumAmountsByPaymentIdAndStatuses(eq(paymentId), any()))
                .thenReturn(new BigDecimal("50.00"));
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        assertThrows(ConflictException.class, () -> service.create(
                paymentId,
                "refund-key",
                new RefundCreateRequest(UUID.randomUUID(), new BigDecimal("60.00"), "refund")));
        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnsupportedRefundDecisionAtStartup() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "FAILED"));

        assertTrue(exception.getMessage().contains("SUCCESS o FAILURE"));
    }

    @Test
    void replayReturnsExistingRefundBeforeValidatingBalance() {
        UUID requestId = UUID.randomUUID();
        String fingerprint = fingerprints.refundFingerprint(
                paymentId, requestId, new BigDecimal("100.00"), "refund");
        Refund existing = new Refund();
        existing.setRequestFingerprint(fingerprint);
        when(refundRepository.findByIdempotencyKey("refund-key")).thenReturn(Optional.of(existing));
        when(refundRepository.sumAmountsByPaymentIdAndStatuses(eq(paymentId), any()))
                .thenReturn(new BigDecimal("100.00"));
        RefundTransactionService service =
                new RefundTransactionService(paymentRepository, refundRepository, fingerprints, "SUCCESS");

        RefundCreationResult result = service.create(
                paymentId,
                "refund-key",
                new RefundCreateRequest(requestId, new BigDecimal("100.00"), "refund"));

        assertTrue(result.replayed());
        assertEquals(existing, result.refund());
        verify(refundRepository, never()).sumAmountsByPaymentIdAndStatuses(eq(paymentId), any());
    }
}
