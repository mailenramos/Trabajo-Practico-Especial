package com.example.msvc.pagos.services;

import com.example.msvc.pagos.entities.Refund;

public record RefundCreationResult(Refund refund, boolean replayed) {
}
