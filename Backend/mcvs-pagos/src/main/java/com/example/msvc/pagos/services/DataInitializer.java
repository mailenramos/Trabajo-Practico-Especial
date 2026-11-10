package com.example.msvc.pagos.services;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.enums.RefundStatus;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.example.msvc.pagos.repositories.RefundRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class DataInitializer implements ApplicationRunner {

    private static final UUID APPROVED_PAYMENT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID APPROVED_PURCHASE_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID REJECTED_PAYMENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REJECTED_PURCHASE_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID REFUND_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REFUND_REQUEST_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID FAILED_REFUND_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID FAILED_REFUND_REQUEST_ID =
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final String APPROVED_PAYMENT_KEY = "seed-payment-approved";
    private static final String REJECTED_PAYMENT_KEY = "seed-payment-rejected";
    private static final String REFUND_KEY = "seed-refund-success";
    private static final String FAILED_REFUND_KEY = "seed-refund-failed";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RequestFingerprintService fingerprints;

    public DataInitializer(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            RequestFingerprintService fingerprints) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.fingerprints = fingerprints;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (paymentRepository.count() != 0 || refundRepository.count() != 0) {
            return;
        }

        Payment approvedPayment = payment(
                APPROVED_PAYMENT_ID,
                APPROVED_PURCHASE_ID,
                APPROVED_PAYMENT_KEY,
                new BigDecimal("50000.00"),
                PaymentStatus.APROBADO);
        Payment rejectedPayment = payment(
                REJECTED_PAYMENT_ID,
                REJECTED_PURCHASE_ID,
                REJECTED_PAYMENT_KEY,
                new BigDecimal("12500.00"),
                PaymentStatus.RECHAZADO);
        List<Payment> savedPayments = paymentRepository.saveAll(List.of(approvedPayment, rejectedPayment));
        Payment managedApprovedPayment = savedPayments.stream()
                .filter(payment -> payment.getId().equals(APPROVED_PAYMENT_ID))
                .findFirst()
                .orElseThrow();

        Refund refund = new Refund();
        refund.setId(REFUND_ID);
        refund.setPayment(managedApprovedPayment);
        refund.setRefundRequestId(REFUND_REQUEST_ID);
        refund.setIdempotencyKey(REFUND_KEY);
        refund.setAmount(new BigDecimal("1000.00"));
        refund.setReason("Reintegro de prueba");
        refund.setStatus(RefundStatus.EXITOSO);
        refund.setAttemptCount(1);
        refund.setRequestFingerprint(fingerprints.refundFingerprint(
                APPROVED_PAYMENT_ID, REFUND_REQUEST_ID, refund.getAmount(), refund.getReason()));
        Refund failedRefund = new Refund();
        failedRefund.setId(FAILED_REFUND_ID);
        failedRefund.setPayment(managedApprovedPayment);
        failedRefund.setRefundRequestId(FAILED_REFUND_REQUEST_ID);
        failedRefund.setIdempotencyKey(FAILED_REFUND_KEY);
        failedRefund.setAmount(new BigDecimal("500.00"));
        failedRefund.setReason("Reintegro de prueba fallido");
        failedRefund.setStatus(RefundStatus.FALLIDO);
        failedRefund.setAttemptCount(1);
        failedRefund.setRequestFingerprint(fingerprints.refundFingerprint(
                APPROVED_PAYMENT_ID,
                FAILED_REFUND_REQUEST_ID,
                failedRefund.getAmount(),
                failedRefund.getReason()));
        refundRepository.saveAll(List.of(refund, failedRefund));
        refundRepository.flush();
    }

    private Payment payment(
            UUID id,
            UUID purchaseId,
            String idempotencyKey,
            BigDecimal amount,
            PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setPurchaseId(purchaseId);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmount(amount);
        payment.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(status);
        payment.setRequestFingerprint(
                fingerprints.paymentFingerprint(purchaseId, amount, payment.getPaymentMethod()));
        return payment;
    }
}
