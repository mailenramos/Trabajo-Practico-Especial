package com.example.msvc.pagos.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.enums.RefundStatus;
import com.example.msvc.pagos.exceptions.ResourceNotFoundException;
import com.example.msvc.pagos.services.RefundCreationResult;
import com.example.msvc.pagos.services.RefundService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RefundController.class)
class RefundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundService refundService;

    @Test
    void createsRefundAndReturnsReplayWithExpectedStatuses() throws Exception {
        Refund refund = refund();
        when(refundService.create(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RefundCreationResult(refund, false))
                .thenReturn(new RefundCreationResult(refund, true));

        mockMvc.perform(post("/internal/pagos/{paymentId}/reintegros", refund.getPayment().getId())
                        .header("Idempotency-Key", "refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refundRequestId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                 "amount":25.0,"reason":"partial refund"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/internal/pagos/{paymentId}/reintegros", refund.getPayment().getId())
                        .header("Idempotency-Key", "refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refundRequestId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                 "amount":25.00,"reason":"partial refund"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void missingIdempotencyKeyOnCreateReturns400() throws Exception {
        mockMvc.perform(post("/internal/pagos/{paymentId}/reintegros", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refundRequestId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                 "amount":25.00,"reason":"partial refund"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.campos").doesNotExist());
    }

    @Test
    void missingRefundReturns404ForReadAndRetry() throws Exception {
        UUID refundId = UUID.randomUUID();
        when(refundService.get(refundId))
                .thenThrow(new ResourceNotFoundException("Reintegro no encontrado"));
        when(refundService.retry(refundId))
                .thenThrow(new ResourceNotFoundException("Reintegro no encontrado"));

        mockMvc.perform(get("/internal/reintegros/{refundId}", refundId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/internal/reintegros/{refundId}/reintentar", refundId))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsExistingRefundWith200() throws Exception {
        Refund refund = refund();
        when(refundService.get(refund.getId())).thenReturn(refund);

        mockMvc.perform(get("/internal/reintegros/{refundId}", refund.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(refund.getId().toString()))
                .andExpect(jsonPath("$.status").value("FALLIDO"));
    }

    @Test
    void retryDoesNotRequireIdempotencyKey() throws Exception {
        UUID refundId = UUID.randomUUID();
        when(refundService.retry(eq(refundId))).thenReturn(refund());

        mockMvc.perform(post("/internal/reintegros/{refundId}/reintentar", refundId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALLIDO"));
    }

    private static Refund refund() {
        Payment payment = new Payment();
        payment.setId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        payment.setStatus(PaymentStatus.APROBADO);
        Refund refund = new Refund();
        refund.setId(UUID.randomUUID());
        refund.setPayment(payment);
        refund.setRefundRequestId(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        refund.setAmount(new BigDecimal("25.00"));
        refund.setStatus(RefundStatus.FALLIDO);
        refund.setReason("partial refund");
        refund.setAttemptCount(1);
        refund.setCreatedAt(Instant.now());
        refund.setUpdatedAt(refund.getCreatedAt());
        return refund;
    }
}
