package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.msvc.pagos.dto.PaymentCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class PaymentServiceTest {

    @Test
    void recoversPersistedIdempotencyWinnerInNewTransaction() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransactionService creator = mock(PaymentTransactionService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        RequestFingerprintService fingerprints = new RequestFingerprintService(new ObjectMapper());
        UUID purchaseId = UUID.randomUUID();
        PaymentCreateRequest request =
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.00"), PaymentMethod.CREDIT_CARD);
        Payment winner = new Payment();
        winner.setRequestFingerprint(
                fingerprints.paymentFingerprint(purchaseId, request.amount(), request.paymentMethod()));
        when(transactionManager.getTransaction(any())).thenReturn(status);
        when(creator.create("key", request)).thenThrow(new DataIntegrityViolationException("collision"));
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(winner));

        PaymentCreationResult result = new PaymentService(
                repository, creator, fingerprints, transactionManager)
                .create(purchaseId, request.amount(), request.paymentMethod(), "key");

        assertEquals(winner, result.payment());
        org.junit.jupiter.api.Assertions.assertTrue(result.replayed());
        verify(transactionManager).commit(status);
    }

    @Test
    void mapsUnresolvablePartialIndexCollisionToConflict() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransactionService creator = mock(PaymentTransactionService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        UUID purchaseId = UUID.randomUUID();
        PaymentCreateRequest request =
                new PaymentCreateRequest(purchaseId, new BigDecimal("25.00"), PaymentMethod.CREDIT_CARD);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        when(creator.create("key", request)).thenThrow(new DataIntegrityViolationException("collision"));
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty());

        PaymentService service = new PaymentService(
                repository,
                creator,
                new RequestFingerprintService(new ObjectMapper()),
                transactionManager);

        assertThrows(ConflictException.class, () -> service.create(
                purchaseId, request.amount(), request.paymentMethod(), "key"));
        verify(transactionManager).commit(status);
    }
}
