package com.example.msvc.pagos.services;

import com.example.msvc.pagos.dto.PaymentCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.exceptions.ResourceNotFoundException;
import com.example.msvc.pagos.repositories.PaymentRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService transactionService;
    private final RequestFingerprintService fingerprints;
    private final TransactionTemplate recoveryTransaction;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentTransactionService transactionService,
            RequestFingerprintService fingerprints,
            PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.fingerprints = fingerprints;
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recoveryTransaction.setReadOnly(true);
    }

    public PaymentCreationResult create(
            UUID purchaseId,
            BigDecimal amount,
            PaymentMethod paymentMethod,
            String idempotencyKey) {
        PaymentCreateRequest request = new PaymentCreateRequest(purchaseId, amount, paymentMethod);
        try {
            return transactionService.create(idempotencyKey, request);
        } catch (DataIntegrityViolationException exception) {
            String fingerprint = fingerprints.paymentFingerprint(purchaseId, amount, paymentMethod);
            Payment winner = recoveryTransaction.execute(
                    status -> paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null));
            if (winner == null) {
                throw new ConflictException("La compra ya tiene un pago aprobado");
            }
            if (!winner.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("La Idempotency-Key ya fue utilizada con otros datos");
            }
            return new PaymentCreationResult(winner, true);
        }
    }

    public Payment get(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado"));
    }

    public List<Payment> findByPurchaseId(UUID purchaseId) {
        return paymentRepository.findAllByPurchaseId(purchaseId);
    }
}
