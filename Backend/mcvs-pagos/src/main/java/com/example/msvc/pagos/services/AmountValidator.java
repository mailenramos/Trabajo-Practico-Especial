package com.example.msvc.pagos.services;

import com.example.msvc.pagos.exceptions.InvalidRequestException;

import java.math.BigDecimal;

public final class AmountValidator {

    private AmountValidator() {
    }

    public static void validatePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException("El monto debe ser positivo");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidRequestException("El monto no puede tener más de dos decimales");
        }
    }
}
