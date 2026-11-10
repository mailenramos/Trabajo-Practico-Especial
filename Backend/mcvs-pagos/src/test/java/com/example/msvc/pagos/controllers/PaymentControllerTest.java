package com.example.msvc.pagos.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.enums.PaymentMethod;
import com.example.msvc.pagos.enums.PaymentStatus;
import com.example.msvc.pagos.exceptions.ConflictException;
import com.example.msvc.pagos.exceptions.InvalidRequestException;
import com.example.msvc.pagos.exceptions.ResourceNotFoundException;
import com.example.msvc.pagos.services.PaymentCreationResult;
import com.example.msvc.pagos.services.PaymentService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.hamcrest.Matchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void createsPaymentWith201AndReplayWith200() throws Exception {
        Payment payment = payment();
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenReturn(new PaymentCreationResult(payment, false))
                .thenReturn(new PaymentCreationResult(payment, true));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":50000.0,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APROBADO"));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":50000.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void rejectedDigitalWalletCreationStillReturns201() throws Exception {
        Payment payment = payment();
        payment.setPaymentMethod(PaymentMethod.DIGITAL_WALLET);
        payment.setStatus(PaymentStatus.RECHAZADO);
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenReturn(new PaymentCreationResult(payment, false));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "wallet-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":50000.00,"paymentMethod":"DIGITAL_WALLET"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECHAZADO"));
    }

    @Test
    void missingIdempotencyKeyReturns400WithErrorShape() throws Exception {
        mockMvc.perform(post("/internal/pagos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":5.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.timestamp").value(Matchers.matchesPattern(".*Z")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.mensaje").value("Falta el header Idempotency-Key"))
                .andExpect(jsonPath("$.campos").doesNotExist());
    }

    @Test
    void invalidPaymentReturnsValidationFields() throws Exception {
        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":0,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.amount").exists());
        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":1.234,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.amount").exists());
    }

    @Test
    void returnsExistingPaymentWith200() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.get(paymentId)).thenReturn(payment());

        mockMvc.perform(get("/internal/pagos/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("APROBADO"));
    }

    @Test
    void returnsEmptyPurchaseListWith200() throws Exception {
        UUID purchaseId = UUID.randomUUID();
        when(paymentService.findByPurchaseId(purchaseId)).thenReturn(List.of());

        mockMvc.perform(get("/internal/pagos/compra/{purchaseId}", purchaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void mapsMissingPaymentAndConflictsToExpectedStatuses() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.get(paymentId)).thenThrow(new ResourceNotFoundException("Pago no encontrado"));
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenThrow(new ConflictException("Compra ya aprobada"));

        mockMvc.perform(get("/internal/pagos/{paymentId}", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":5.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void mapsInvalidPathAndMalformedJsonTo400() throws Exception {
        mockMvc.perform(get("/internal/pagos/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("Solicitud inválida"));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.mensaje").value("Solicitud inválida"));
    }

    @Test
    void mapsMissingStaticResourceToNotFound() throws Exception {
        mockMvc.perform(get("/missing-static-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.mensaje").value("Recurso no encontrado"));
    }

    @Test
    void mapsUnsupportedRequestMethodToMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/internal/pagos/{paymentId}",
                        "c9d4c779-11f4-4a2d-beb3-c566657d1146"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.mensaje").value("Método no permitido"));
    }

    @Test
    void mapsInvalidRequestExceptionToBadRequest() throws Exception {
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenThrow(new InvalidRequestException("El monto debe ser positivo"));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":5.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El monto debe ser positivo"));
    }

    @Test
    void mapsDataIntegrityViolationToConflict() throws Exception {
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("database detail"));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":5.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.mensaje").value("Conflicto de integridad de datos"));
    }

    @Test
    void mapsUnexpectedExceptionToGenericInternalError() throws Exception {
        when(paymentService.create(any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("sensitive exception detail"));

        mockMvc.perform(post("/internal/pagos")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purchaseId":"c9d4c779-11f4-4a2d-beb3-c566657d1146",
                                 "amount":5.00,"paymentMethod":"CREDIT_CARD"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.mensaje").value("Error interno"))
                .andExpect(content().string(Matchers.not(
                        Matchers.containsString("sensitive exception detail"))));
    }

    private static Payment payment() {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setPurchaseId(UUID.fromString("c9d4c779-11f4-4a2d-beb3-c566657d1146"));
        payment.setAmount(new BigDecimal("50000.00"));
        payment.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.APROBADO);
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(payment.getCreatedAt());
        return payment;
    }
}
