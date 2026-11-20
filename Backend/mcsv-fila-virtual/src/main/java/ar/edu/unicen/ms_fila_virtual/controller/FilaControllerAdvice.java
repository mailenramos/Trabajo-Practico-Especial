package ar.edu.unicen.ms_fila_virtual.controller;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ar.edu.unicen.ms_fila_virtual.dto.ErrorResponse;
import ar.edu.unicen.ms_fila_virtual.dto.ErrorResponse.CampoError;
import ar.edu.unicen.ms_fila_virtual.service.FilaConflictException;
import ar.edu.unicen.ms_fila_virtual.service.FilaNoEncontradaException;
import ar.edu.unicen.ms_fila_virtual.service.Ms1NoDisponibleException;
import ar.edu.unicen.ms_fila_virtual.service.PermisoFilaException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class FilaControllerAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilaControllerAdvice.class);
    private final Clock clock;
    public FilaControllerAdvice(Clock clock) { this.clock = clock; }

    @ExceptionHandler(AccesoException.class)
    ResponseEntity<ErrorResponse> acceso(AccesoException e) { return respuesta(e.status(), e.getMessage(), null); }

    @ExceptionHandler(FilaNoEncontradaException.class)
    ResponseEntity<ErrorResponse> noEncontrada(FilaNoEncontradaException e) { return respuesta(HttpStatus.NOT_FOUND, e.getMessage(), null); }

    @ExceptionHandler(FilaConflictException.class)
    ResponseEntity<ErrorResponse> conflicto(FilaConflictException e) { return respuesta(HttpStatus.CONFLICT, e.getMessage(), null); }

    @ExceptionHandler(PermisoFilaException.class)
    ResponseEntity<ErrorResponse> prohibido(PermisoFilaException e) { return respuesta(HttpStatus.FORBIDDEN, e.getMessage(), null); }

    @ExceptionHandler(Ms1NoDisponibleException.class)
    ResponseEntity<ErrorResponse> ms1NoDisponible(Ms1NoDisponibleException e) { return respuesta(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), null); }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> validacion(Exception e) {
        List<CampoError> campos = switch (e) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> new CampoError(error.getField(), error.getDefaultMessage())).distinct().toList();
            case ConstraintViolationException invalid -> invalid.getConstraintViolations().stream()
                    .map(error -> new CampoError(error.getPropertyPath().toString(), error.getMessage())).distinct().toList();
            default -> List.of();
        };
        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud contiene datos inválidos.", campos);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> solicitudInvalida(Exception e) {
        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud contiene datos inválidos.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> tipoInvalido(Exception e) {
        return respuesta(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El tipo de contenido no está soportado.", null);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ErrorResponse> errorInterno(RuntimeException e) {
        StackTraceElement[] traza = e.getStackTrace();
        LOGGER.error("Error inesperado HTTP 500: tipo={}, ubicación={}", e.getClass().getName(),
                traza.length == 0 ? "desconocida" : traza[0]);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno.", null);
    }

    private ResponseEntity<ErrorResponse> respuesta(HttpStatus status, String mensaje, List<CampoError> campos) {
        return ResponseEntity.status(status).body(new ErrorResponse(clock.instant(), status.value(), status.getReasonPhrase(), mensaje, campos));
    }
}
