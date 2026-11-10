package com.example.msvc.pagos.services;

import com.example.msvc.pagos.entities.Payment;

public record PaymentCreationResult(Payment payment, boolean replayed) {
}
