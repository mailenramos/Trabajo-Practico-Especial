package com.example.msvc.pagos.repositories;

import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.RefundStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByRefundRequestId(UUID refundRequestId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r "
            + "where r.payment.id = :paymentId and r.status in :statuses")
    BigDecimal sumAmountsByPaymentIdAndStatuses(
            @Param("paymentId") UUID paymentId,
            @Param("statuses") Collection<RefundStatus> statuses);

    @Query("select r.payment.id as paymentId from Refund r where r.id = :refundId")
    Optional<PaymentIdProjection> findPaymentIdByRefundId(@Param("refundId") UUID refundId);
}
