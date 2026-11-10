package com.example.msvc.pagos.services;

import com.example.msvc.pagos.dto.RefundCreateRequest;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.enums.RefundStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.exceptions.ResourceNotFoundException;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.example.msvc.pagos.repositories.PaymentIdProjection;
import com.example.msvc.pagos.repositories.RefundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundTransactionService {

    private static final List<RefundStatus> BALANCE_STATUSES =
            List.of(RefundStatus.EXITOSO, RefundStatus.PENDIENTE);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RequestFingerprintService fingerprints;
    private final String refundDecision;

    public RefundTransactionService(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            RequestFingerprintService fingerprints,
            @Value("${ticketya.payment-simulation.refund-decision:SUCCESS}") String refundDecision) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.fingerprints = fingerprints;
        this.refundDecision = refundDecision;
        if (!"SUCCESS".equalsIgnoreCase(refundDecision)
                && !"FAILURE".equalsIgnoreCase(refundDecision)) {
            throw new IllegalStateException(
                    "Configuración inválida para ticketya.payment-simulation.refund-decision: "
                            + "use SUCCESS o FAILURE");
        }
    }

    @Transactional
    public RefundCreationResult create(
            UUID paymentId,
            String idempotencyKey,
            RefundCreateRequest request) {
        AmountValidator.validatePositiveAmount(request.amount());
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado"));
        String fingerprint = fingerprints.refundFingerprint(
                paymentId, request.refundRequestId(), request.amount(), request.reason());

        var existing = refundRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Refund refund = existing.get();
            if (!refund.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("La Idempotency-Key ya fue utilizada con otros datos");
            }
            return new RefundCreationResult(refund, true);
        }

        if (refundRepository.findByRefundRequestId(request.refundRequestId()).isPresent()) {
            throw new ConflictException("refundRequestId ya fue utilizado");
        }
        if (payment.getStatus() != PaymentStatus.APROBADO) {
            throw new ConflictException("El pago no está aprobado");
        }
        BigDecimal refunded = refundRepository.sumAmountsByPaymentIdAndStatuses(paymentId, BALANCE_STATUSES);
        if (refunded.add(request.amount()).compareTo(payment.getAmount()) > 0) {
            throw new ConflictException("El reintegro supera el saldo disponible");
        }

        Refund refund = new Refund();
        refund.setId(UUID.randomUUID());
        refund.setPayment(payment);
        refund.setRefundRequestId(request.refundRequestId());
        refund.setIdempotencyKey(idempotencyKey);
        refund.setRequestFingerprint(fingerprint);
        refund.setAmount(request.amount().setScale(2, RoundingMode.UNNECESSARY));
        refund.setReason(request.reason());
        refund.setAttemptCount(1);
        refund.setStatus(resolveStatus());
        return new RefundCreationResult(refundRepository.saveAndFlush(refund), false);
    }

    @Transactional
    public Refund retry(UUID refundId) {
        PaymentIdProjection reference = refundRepository.findPaymentIdByRefundId(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Reintegro no encontrado"));
        UUID paymentId = reference.getPaymentId();
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado"));

        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Reintegro no encontrado"));
        if (refund.getStatus() != RefundStatus.FALLIDO) {
            throw new ConflictException("Solo se pueden reintentar reintegros FALLIDO");
        }

        BigDecimal refundedByOthers =
                refundRepository.sumAmountsByPaymentIdAndStatuses(paymentId, BALANCE_STATUSES);
        if (refundedByOthers.add(refund.getAmount()).compareTo(payment.getAmount()) > 0) {
            throw new ConflictException("El reintegro supera el saldo disponible");
        }

        refund.setAttemptCount(refund.getAttemptCount() + 1);
        refund.setStatus(resolveStatus());
        return refundRepository.saveAndFlush(refund);
    }

    @Transactional(readOnly = true)
    public Refund get(UUID refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Reintegro no encontrado"));
    }

    private RefundStatus resolveStatus() {
        if ("SUCCESS".equalsIgnoreCase(refundDecision)) {
            return RefundStatus.EXITOSO;
        }
        return RefundStatus.FALLIDO;
    }
}
