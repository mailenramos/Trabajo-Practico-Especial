package com.example.msvc.pagos.repositories;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByPurchaseIdAndStatus(UUID purchaseId, PaymentStatus status);

    List<Payment> findAllByPurchaseId(UUID purchaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") UUID paymentId);
}
