package com.example.msvc.pagos.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.msvc.pagos.dto.RefundCreateRequest;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.repositories.RefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class RefundServiceTest {

    @Test
    void recoversPersistedIdempotencyWinnerInNewTransaction() {
        RefundRepository repository = mock(RefundRepository.class);
        RefundTransactionService creator = mock(RefundTransactionService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        RequestFingerprintService fingerprints = new RequestFingerprintService(new ObjectMapper());
        UUID paymentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RefundCreateRequest request = new RefundCreateRequest(
                requestId, new BigDecimal("25.00"), "partial");
        Refund winner = new Refund();
        winner.setRequestFingerprint(
                fingerprints.refundFingerprint(paymentId, requestId, request.amount(), request.reason()));
        when(transactionManager.getTransaction(any())).thenReturn(status);
        when(creator.create(paymentId, "key", request))
                .thenThrow(new DataIntegrityViolationException("collision"));
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(winner));

        RefundCreationResult result = new RefundService(
                repository, creator, fingerprints, transactionManager)
                .create(paymentId, requestId, request.amount(), request.reason(), "key");

        assertEquals(winner, result.refund());
        assertTrue(result.replayed());
        verify(transactionManager).commit(status);
    }
}
