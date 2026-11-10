package com.example.msvc.pagos.controllers;

import com.example.msvc.pagos.dto.RefundCreateRequest;
import com.example.msvc.pagos.dto.RefundResponse;
import com.example.msvc.pagos.entities.Refund;
import com.example.msvc.pagos.exceptions.ApiError;
import com.example.msvc.pagos.services.RefundCreationResult;
import com.example.msvc.pagos.services.RefundService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@RequestMapping("/internal")
@Tag(name = "Reintegros")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/pagos/{paymentId}/reintegros")
    @Operation(summary = "Crear un reintegro",
            description = "Registra el reintegro parcial o total de un pago aprobado y valida el saldo disponible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reintegro ya existente devuelto por idempotencia",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"33333333-3333-4333-8333-333333333333","paymentId":"11111111-1111-4111-8111-111111111111","refundRequestId":"55555555-5555-4555-8555-555555555555","amount":500.00,"status":"EXITOSO","reason":"Prueba de reintegro","attemptCount":1,"createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "201", description = "Reintegro creado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"33333333-3333-4333-8333-333333333333","paymentId":"11111111-1111-4111-8111-111111111111","refundRequestId":"55555555-5555-4555-8555-555555555555","amount":500.00,"status":"EXITOSO","reason":"Prueba de reintegro","attemptCount":1,"createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "Solicitud inválida",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Error de validación","campos":{"amount":"must be greater than 0"}}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Pago no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":404,"error":"Not Found","mensaje":"Pago no encontrado"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "Conflicto de idempotencia o saldo de reintegro",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":409,"error":"Conflict","mensaje":"El reintegro supera el saldo disponible"}
                                    """)))
    })
    public ResponseEntity<RefundResponse> create(
            @Parameter(description = "UUID del pago asociado.", required = true,
                    example = "11111111-1111-4111-8111-111111111111")
            @PathVariable UUID paymentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Datos del reintegro a registrar.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RefundCreateRequest.class),
                            examples = @ExampleObject(value = """
                                    {"refundRequestId":"55555555-5555-4555-8555-555555555555","amount":500.00,"reason":"Prueba de reintegro"}
                                    """)))
            @Valid @RequestBody RefundCreateRequest request,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "Clave única obligatoria para hacer idempotente la creación del reintegro.",
                    example = "refund-key-123")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        RefundCreationResult result = refundService.create(
                paymentId,
                request.refundRequestId(),
                request.amount(),
                request.reason(),
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.refund()));
    }

    @GetMapping("/reintegros/{refundId}")
    @Operation(summary = "Consultar un reintegro",
            description = "Devuelve los datos registrados del reintegro identificado por refundId.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reintegro encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"33333333-3333-4333-8333-333333333333","paymentId":"11111111-1111-4111-8111-111111111111","refundRequestId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","amount":500.00,"status":"EXITOSO","reason":"Reintegro parcial","attemptCount":1,"createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:00:00Z"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "UUID inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Solicitud inválida"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Reintegro no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":404,"error":"Not Found","mensaje":"Reintegro no encontrado"}
                                    """)))
    })
    public RefundResponse get(
            @Parameter(description = "UUID del reintegro.", required = true,
                    example = "33333333-3333-4333-8333-333333333333")
            @PathVariable UUID refundId) {
        return toResponse(refundService.get(refundId));
    }

    @PostMapping("/reintegros/{refundId}/reintentar")
    @Operation(summary = "Reintentar un reintegro fallido",
            description = "Vuelve a procesar un reintegro FALLIDO e incrementa su contador de intentos. Este endpoint no requiere Idempotency-Key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reintegro reintentado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefundResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"33333333-3333-4333-8333-333333333333","paymentId":"11111111-1111-4111-8111-111111111111","refundRequestId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","amount":500.00,"status":"EXITOSO","reason":"Reintegro parcial","attemptCount":2,"createdAt":"2026-01-01T12:00:00Z","updatedAt":"2026-01-01T12:01:00Z"}
                                    """))),
            @ApiResponse(responseCode = "400", description = "UUID inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":400,"error":"Bad Request","mensaje":"Solicitud inválida"}
                                    """))),
            @ApiResponse(responseCode = "404", description = "Reintegro no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":404,"error":"Not Found","mensaje":"Reintegro no encontrado"}
                                    """))),
            @ApiResponse(responseCode = "409", description = "El reintegro no está en estado FALLIDO",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"timestamp":"2026-01-01T12:00:00Z","status":409,"error":"Conflict","mensaje":"Solo se pueden reintentar reintegros FALLIDO"}
                                    """)))
    })
    public RefundResponse retry(
            @Parameter(description = "UUID del reintegro FALLIDO.", required = true,
                    example = "33333333-3333-4333-8333-333333333333")
            @PathVariable UUID refundId) {
        return toResponse(refundService.retry(refundId));
    }

    private static RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPayment().getId(),
                refund.getRefundRequestId(),
                refund.getAmount(),
                refund.getStatus(),
                refund.getReason(),
                refund.getAttemptCount(),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }
}
