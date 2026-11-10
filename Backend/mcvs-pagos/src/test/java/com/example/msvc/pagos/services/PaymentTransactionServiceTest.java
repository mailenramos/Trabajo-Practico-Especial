package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.msvc.pagos.dto.PaymentCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.exceptions.InvalidRequestException;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PaymentTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private RequestFingerprintService fingerprints;
    private UUID purchaseId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fingerprints = new RequestFingerprintService(new ObjectMapper());
        purchaseId = UUID.randomUUID();
        when(paymentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.existsByPurchaseIdAndStatus(purchaseId, PaymentStatus.APROBADO))
                .thenReturn(false);
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"CREDIT_CARD", "DEBIT_CARD", "BANK_TRANSFER"})
    void createsResolvedApprovedPaymentForNonWalletMethods(PaymentMethod method) {
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "APPROVED");

        PaymentCreationResult result = service.create(
                "key-" + method,
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.10"), method));

        assertFalse(result.replayed());
        assertEquals(PaymentStatus.APROBADO, result.payment().getStatus());
        assertEquals(new BigDecimal("25.10"), result.payment().getAmount());
        verify(paymentRepository).saveAndFlush(result.payment());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"CREDIT_CARD", "DEBIT_CARD", "BANK_TRANSFER"})
    void persistsRejectedPaymentAndAllowsASeparateAttempt(PaymentMethod method) {
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "REJECTED");

        PaymentCreationResult result = service.create(
                "key-" + method,
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.10"), method));

        assertEquals(PaymentStatus.RECHAZADO, result.payment().getStatus());
        assertFalse(result.replayed());
    }

    @Test
    void alwaysRejectsDigitalWalletEvenWhenDecisionIsApproved() {
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "APPROVED");

        PaymentCreationResult result = service.create(
                "wallet-key",
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.10"), PaymentMethod.DIGITAL_WALLET));

        assertFalse(result.replayed());
        assertEquals(PaymentStatus.RECHAZADO, result.payment().getStatus());
        verify(paymentRepository).saveAndFlush(result.payment());
    }

    @Test
    void returnsSamePayloadReplayBeforeCheckingApprovedPurchase() {
        Payment existing = new Payment();
        existing.setId(UUID.randomUUID());
        existing.setRequestFingerprint(
                fingerprints.paymentFingerprint(purchaseId, new BigDecimal("25.1"), PaymentMethod.CREDIT_CARD));
        when(paymentRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.of(existing));
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "APPROVED");

        PaymentCreationResult result = service.create(
                "key-3",
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.10"), PaymentMethod.CREDIT_CARD));

        assertTrue(result.replayed());
        assertEquals(existing, result.payment());
        verify(paymentRepository, never()).existsByPurchaseIdAndStatus(any(), any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDifferentPayloadForExistingKey() {
        Payment existing = new Payment();
        existing.setRequestFingerprint(
                fingerprints.paymentFingerprint(purchaseId, new BigDecimal("25.10"), PaymentMethod.CREDIT_CARD));
        when(paymentRepository.findByIdempotencyKey("key-4")).thenReturn(Optional.of(existing));
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "APPROVED");

        assertThrows(ConflictException.class, () -> service.create(
                "key-4",
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.10"), PaymentMethod.DEBIT_CARD)));
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidAmountBeforePersisting() {
        PaymentTransactionService service =
                new PaymentTransactionService(paymentRepository, fingerprints, "APPROVED");

        assertThrows(InvalidRequestException.class, () -> service.create(
                "key-5",
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.101"), PaymentMethod.CREDIT_CARD)));
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnsupportedPaymentDecisionAtStartup() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new PaymentTransactionService(paymentRepository, fingerprints, "INVALID"));

        assertTrue(exception.getMessage().contains("APPROVED o REJECTED"));
    }
}
