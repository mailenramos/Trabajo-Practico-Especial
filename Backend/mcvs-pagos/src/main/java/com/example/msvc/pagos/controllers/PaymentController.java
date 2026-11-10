package com.example.msvc.pagos.controllers;

import com.example.msvc.pagos.dto.PaymentCreateRequest;
import com.example.msvc.pagos.dto.PaymentResponse;
import com.example.msvc.pagos.exceptions.ApiError;
import com.example.msvc.pagos.entities.Payment;
import com.example.msvc.pagos.services.PaymentCreationResult;
import com.example.msvc.pagos.services.PaymentService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/pagos")
@Tag(name = "Pagos")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Crear un pago",
            description = "Registra y simula el cobro de una compra. Una repetición con la misma clave y datos devuelve el pago original.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pago ya existente devuelto por idempotencia",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"11111111-1111-4111-8111-111111111111","purchaseId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","amount":1500.00,"paymentMethod":"CREDIT_CARD","status":"APROBADO","createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "201", description = "Pago creado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"11111111-1111-4111-8111-111111111111","purchaseId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","amount":1500.00,"paymentMethod":"CREDIT_CARD","status":"APROBADO","createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Error de validación","campos":{"amount":"must be greater than 0"}}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Conflicto de idempotencia o compra ya aprobada",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":409,"error":"Conflict","mensaje":"La compra ya tiene un pago aprobado"}
                                    """)))
    })
    public ResponseEntity<PaymentResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Datos del pago a registrar.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PaymentCreateRequest.class),
                            examples = @ExampleObject(value = """
                                    {"purchaseId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","amount":1500.00,"paymentMethod":"CREDIT_CARD"}
                                    """)))
            @Valid @RequestBody PaymentCreateRequest request,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "Clave única obligatoria para hacer idempotente la creación del pago.",
                    example = "payment-key-123")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentCreationResult result = paymentService.create(
                request.purchaseId(), request.amount(), request.paymentMethod(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.payment()));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Consultar un pago por UUID",
            description = "Devuelve los datos registrados del pago identificado por paymentId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pago encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaymentResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"11111111-1111-4111-8111-111111111111","purchaseId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","amount":1500.00,"paymentMethod":"CREDIT_CARD","status":"APROBADO","createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "UUID inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Solicitud inválida"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Pago no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":404,"error":"Not Found","mensaje":"Pago no encontrado"}
                                    """)))
    })
    public PaymentResponse get(
            @Parameter(description = "UUID del pago.", required = true,
                    example = "11111111-1111-4111-8111-111111111111")
            @PathVariable UUID paymentId) {
        return toResponse(paymentService.get(paymentId));
    }

    @GetMapping("/compra/{purchaseId}")
    @Operation(summary = "Listar pagos por compra",
            description = "Devuelve los pagos asociados a purchaseId; si no hay resultados, devuelve una lista vacía.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagos de la compra; lista vacía si no hay resultados",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PaymentResponse.class)),
                            examples = @ExampleObject(value = """
                                    [{"id":"11111111-1111-4111-8111-111111111111","purchaseId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","amount":1500.00,"paymentMethod":"CREDIT_CARD","status":"APROBADO","createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}]
                                    """))),
            @ApiResponse(responseCode = "400", description = "UUID inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Solicitud inválida"}
                                    """)))
    })
    public List<PaymentResponse> findByPurchaseId(
            @Parameter(description = "UUID de la compra.", required = true,
                    example = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            @PathVariable UUID purchaseId) {
        return paymentService.findByPurchaseId(purchaseId).stream()
                .map(PaymentController::toResponse)
                .toList();
    }

    private static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPurchaseId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
