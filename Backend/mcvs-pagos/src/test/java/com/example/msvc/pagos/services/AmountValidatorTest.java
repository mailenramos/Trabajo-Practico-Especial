package com.example.msvc.pagos.services;

import com.example.msvc.pagos.exceptions.InvalidRequestException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class AmountValidatorTest {

    @Test
    void acceptsPositiveAmountsWithAtMostTwoMeaningfulDecimals() {
        assertDoesNotThrow(() -> AmountValidator.validatePositiveAmount(new BigDecimal("50000.0")));
        assertDoesNotThrow(() -> AmountValidator.validatePositiveAmount(new BigDecimal("50000.00")));
        assertDoesNotThrow(() -> AmountValidator.validatePositiveAmount(new BigDecimal("1.23")));
    }

    @Test
    void rejectsNullZeroNegativeAndAmountsWithMoreThanTwoDecimals() {
        assertThrows(InvalidRequestException.class, () -> AmountValidator.validatePositiveAmount(null));
        assertThrows(InvalidRequestException.class, () -> AmountValidator.validatePositiveAmount(BigDecimal.ZERO));
        assertThrows(InvalidRequestException.class, () -> AmountValidator.validatePositiveAmount(new BigDecimal("-0.01")));
        assertThrows(InvalidRequestException.class, () -> AmountValidator.validatePositiveAmount(new BigDecimal("1.234")));
    }
}
