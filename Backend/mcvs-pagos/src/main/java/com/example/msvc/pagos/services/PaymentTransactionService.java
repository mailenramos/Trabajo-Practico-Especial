package com.example.msvc.pagos.services;

import com.example.msvc.pagos.dto.PaymentCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.repositories.PaymentRepository;

import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final RequestFingerprintService fingerprints;
    private final String paymentDecision;

    public PaymentTransactionService(
            PaymentRepository paymentRepository,
            RequestFingerprintService fingerprints,
            @Value("${ticketya.payment-simulation.payment-decision:APPROVED}") String paymentDecision) {
        this.paymentRepository = paymentRepository;
        this.fingerprints = fingerprints;
        this.paymentDecision = paymentDecision;
        if (!"APPROVED".equalsIgnoreCase(paymentDecision)
                && !"REJECTED".equalsIgnoreCase(paymentDecision)) {
            throw new IllegalStateException(
                    "Configuración inválida para ticketya.payment-simulation.payment-decision: "
                            + "use APPROVED o REJECTED");
        }
    }

    @Transactional
    public PaymentCreationResult create(String idempotencyKey, PaymentCreateRequest request) {
        AmountValidator.validatePositiveAmount(request.amount());
        String fingerprint = fingerprints.paymentFingerprint(
                request.purchaseId(), request.amount(), request.paymentMethod());

        var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("La Idempotency-Key ya fue utilizada con otros datos");
            }
            return new PaymentCreationResult(payment, true);
        }

        if (paymentRepository.existsByPurchaseIdAndStatus(request.purchaseId(), PaymentStatus.APROBADO)) {
            throw new ConflictException("La compra ya tiene un pago aprobado");
        }

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setPurchaseId(request.purchaseId());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setRequestFingerprint(fingerprint);
        payment.setAmount(request.amount().setScale(2, RoundingMode.UNNECESSARY));
        payment.setPaymentMethod(request.paymentMethod());
        payment.setStatus(resolveStatus(request.paymentMethod()));
        return new PaymentCreationResult(paymentRepository.saveAndFlush(payment), false);
    }

    private PaymentStatus resolveStatus(PaymentMethod paymentMethod) {
        if (paymentMethod == PaymentMethod.DIGITAL_WALLET) {
            return PaymentStatus.RECHAZADO;
        }
        if ("APPROVED".equalsIgnoreCase(paymentDecision)) {
            return PaymentStatus.APROBADO;
        }
        return PaymentStatus.RECHAZADO;
    }
}
