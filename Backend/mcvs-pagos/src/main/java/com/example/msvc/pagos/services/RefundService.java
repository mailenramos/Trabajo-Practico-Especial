package com.example.msvc.pagos.services;

import com.example.msvc.pagos.dto.RefundCreateRequest;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.repositories.RefundRepository;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundService {

    private final RefundRepository refundRepository;
    private final RefundTransactionService transactionService;
    private final RequestFingerprintService fingerprints;
    private final TransactionTemplate recoveryTransaction;

    public RefundService(
            RefundRepository refundRepository,
            RefundTransactionService transactionService,
            RequestFingerprintService fingerprints,
            PlatformTransactionManager transactionManager) {
        this.refundRepository = refundRepository;
        this.transactionService = transactionService;
        this.fingerprints = fingerprints;
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recoveryTransaction.setReadOnly(true);
    }

    public RefundCreationResult create(
            UUID paymentId,
            UUID refundRequestId,
            BigDecimal amount,
            String reason,
            String idempotencyKey) {
        RefundCreateRequest request = new RefundCreateRequest(refundRequestId, amount, reason);
        try {
            return transactionService.create(paymentId, idempotencyKey, request);
        } catch (DataIntegrityViolationException exception) {
            String fingerprint = fingerprints.refundFingerprint(paymentId, refundRequestId, amount, reason);
            Refund winner = recoveryTransaction.execute(
                    status -> refundRepository.findByIdempotencyKey(idempotencyKey).orElse(null));
            if (winner == null) {
                throw new ConflictException("refundRequestId ya fue utilizado");
            }
            if (!winner.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("La Idempotency-Key ya fue utilizada con otros datos");
            }
            return new RefundCreationResult(winner, true);
        }
    }

    public Refund retry(UUID refundId) {
        return transactionService.retry(refundId);
    }

    public Refund get(UUID refundId) {
        return transactionService.get(refundId);
    }
}
