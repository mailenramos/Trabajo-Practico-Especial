package com.example.msvc.pagos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.enums.RefundStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.repositories.PaymentRepository;
import com.example.msvc.pagos.repositories.RefundRepository;
import com.example.msvc.pagos.services.PaymentCreationResult;
import com.example.msvc.pagos.services.PaymentService;
import com.example.msvc.pagos.services.RefundService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRESQL_TESTS", matches = "true")
class PostgreSqlConcurrencyTest {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTables() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void simultaneousSamePaymentKeyPersistsOnlyOnePayment() throws Exception {
        UUID purchaseId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentCreationResult> first = executor.submit(() -> {
                start.await();
                return paymentService.create(
                        purchaseId, new BigDecimal("50.00"), PaymentMethod.CREDIT_CARD, "same-key");
            });
            Future<PaymentCreationResult> second = executor.submit(() -> {
                start.await();
                return paymentService.create(
                        purchaseId, new BigDecimal("50.0"), PaymentMethod.CREDIT_CARD, "same-key");
            });
            start.countDown();

            assertEquals(1, countReplayed(first.get(), second.get()));
            assertEquals(1, paymentRepository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repositoryQueriesReturnExpectedRowsAndAcquirePaymentLock() {
        UUID purchaseId = UUID.randomUUID();
        Payment paymentToSave = approvedPayment(new BigDecimal("100.00"));
        paymentToSave.setPurchaseId(purchaseId);
        paymentToSave.setIdempotencyKey("repository-payment-" + UUID.randomUUID());
        Payment payment = paymentRepository.saveAndFlush(paymentToSave);

        assertEquals(payment.getId(), paymentRepository.findByIdempotencyKey(
                payment.getIdempotencyKey()).orElseThrow().getId());
        assertTrue(paymentRepository.existsByPurchaseIdAndStatus(purchaseId, PaymentStatus.APROBADO));
        assertEquals(List.of(payment.getId()), paymentRepository.findAllByPurchaseId(purchaseId)
                .stream().map(Payment::getId).toList());
        Payment lockedPayment = new TransactionTemplate(transactionManager)
                .execute(status -> paymentRepository.findByIdForUpdate(payment.getId()).orElseThrow());
        assertEquals(payment.getId(), lockedPayment.getId());

        Refund successful = refund(payment, RefundStatus.EXITOSO, new BigDecimal("10.00"));
        Refund pending = refund(payment, RefundStatus.PENDIENTE, new BigDecimal("20.00"));
        Refund failed = refund(payment, RefundStatus.FALLIDO, new BigDecimal("30.00"));
        refundRepository.saveAllAndFlush(List.of(successful, pending, failed));

        assertEquals(successful.getId(), refundRepository.findByIdempotencyKey(
                successful.getIdempotencyKey()).orElseThrow().getId());
        assertEquals(pending.getId(), refundRepository.findByRefundRequestId(
                pending.getRefundRequestId()).orElseThrow().getId());
        assertEquals(payment.getId(), refundRepository.findPaymentIdByRefundId(failed.getId())
                .orElseThrow().getPaymentId());
        assertEquals(new BigDecimal("30.00"), refundRepository.sumAmountsByPaymentIdAndStatuses(
                payment.getId(), List.of(RefundStatus.EXITOSO, RefundStatus.PENDIENTE)));
    }

    @Test
    void simultaneousDifferentKeysCannotApproveSamePurchase() throws Exception {
        UUID purchaseId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> createConcurrentPayment(
                    start, purchaseId, "first-key"));
            Future<Object> second = executor.submit(() -> createConcurrentPayment(
                    start, purchaseId, "second-key"));
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());

            assertEquals(1, outcomes.stream().filter(PaymentCreationResult.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(ConflictException.class::isInstance).count());
            assertEquals(1, paymentRepository.count());
            assertEquals(1, paymentRepository.findAllByPurchaseId(purchaseId).stream()
                    .filter(payment -> payment.getStatus() == PaymentStatus.APROBADO)
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRefundsCannotExceedPaidAmount() throws Exception {
        Payment payment = paymentRepository.saveAndFlush(approvedPayment(new BigDecimal("100.00")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> createConcurrentRefund(start, payment, "refund-a"));
            Future<Object> second = executor.submit(() -> createConcurrentRefund(start, payment, "refund-b"));
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());

            assertEquals(1, outcomes.stream().filter(Refund.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(ConflictException.class::isInstance).count());
            assertEquals(new BigDecimal("60.00"),
                    refundRepository.sumAmountsByPaymentIdAndStatuses(
                            payment.getId(), List.of(RefundStatus.EXITOSO, RefundStatus.PENDIENTE)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRetriesOfSameRefundOnlyResolveOnce() throws Exception {
        Payment payment = paymentRepository.saveAndFlush(approvedPayment(new BigDecimal("100.00")));
        Refund refundToSave = new Refund();
        refundToSave.setId(UUID.randomUUID());
        refundToSave.setPayment(payment);
        refundToSave.setRefundRequestId(UUID.randomUUID());
        refundToSave.setIdempotencyKey("failed-refund-key");
        refundToSave.setRequestFingerprint("failed-fingerprint");
        refundToSave.setAmount(new BigDecimal("25.00"));
        refundToSave.setReason("retry");
        refundToSave.setAttemptCount(1);
        refundToSave.setStatus(RefundStatus.FALLIDO);
        Refund failedRefund = refundRepository.saveAndFlush(refundToSave);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> retryConcurrent(start, failedRefund.getId()));
            Future<Object> second = executor.submit(() -> retryConcurrent(start, failedRefund.getId()));
            start.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());

            assertEquals(1, outcomes.stream().filter(Refund.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(ConflictException.class::isInstance).count());
            Refund resolved = refundRepository.findById(failedRefund.getId()).orElseThrow();
            assertEquals(RefundStatus.EXITOSO, resolved.getStatus());
            assertEquals(2, resolved.getAttemptCount());
        } finally {
            executor.shutdownNow();
        }
    }

    private Object createConcurrentPayment(CountDownLatch start, UUID purchaseId, String key) {
        try {
            start.await();
            return paymentService.create(purchaseId, new BigDecimal("50.00"), PaymentMethod.CREDIT_CARD, key);
        } catch (ConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Object createConcurrentRefund(CountDownLatch start, Payment payment, String key) {
        try {
            start.await();
            return refundService.create(
                    payment.getId(), UUID.randomUUID(), new BigDecimal("60.00"), "partial", key).refund();
        } catch (ConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Object retryConcurrent(CountDownLatch start, UUID refundId) {
        try {
            start.await();
            return refundService.retry(refundId);
        } catch (ConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static long countReplayed(PaymentCreationResult first, PaymentCreationResult second) {
        return List.of(first, second).stream().filter(result -> result.replayed()).count();
    }

    private static Payment approvedPayment(BigDecimal amount) {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setPurchaseId(UUID.randomUUID());
        payment.setIdempotencyKey("integration-" + UUID.randomUUID());
        payment.setRequestFingerprint("integration-fingerprint");
        payment.setAmount(amount);
        payment.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.APROBADO);
        return payment;
    }

    private static Refund refund(Payment payment, RefundStatus status, BigDecimal amount) {
        Refund refund = new Refund();
        refund.setId(UUID.randomUUID());
        refund.setPayment(payment);
        refund.setRefundRequestId(UUID.randomUUID());
        refund.setIdempotencyKey("repository-refund-" + UUID.randomUUID());
        refund.setRequestFingerprint("repository-fingerprint");
        refund.setAmount(amount);
        refund.setReason("repository test");
        refund.setStatus(status);
        return refund;
    }
}
