package ar.edu.unicen.ms_disponibilidad_reservas.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ar.edu.unicen.ms_disponibilidad_reservas.dto.ErrorResponse;
import ar.edu.unicen.ms_disponibilidad_reservas.dto.ErrorResponse.CampoError;
import ar.edu.unicen.ms_disponibilidad_reservas.service.DisponibilidadNoRegistradaException;
import ar.edu.unicen.ms_disponibilidad_reservas.service.InicializacionConflictException;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaConflictException;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaLiberadaException;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaNoEncontradaException;
import ar.edu.unicen.ms_disponibilidad_reservas.service.ReservaVencidaException;
import jakarta.validation.ConstraintViolationException;

/** Applies MS2's documented error shape to HTTP requests. */
@RestControllerAdvice
public class DisponibilidadControllerAdvice {

    @ExceptionHandler(InicializacionConflictException.class)
    public ResponseEntity<ErrorResponse> conflictoInicializacion(InicializacionConflictException exception) {
        return respuesta(HttpStatus.CONFLICT, "El evento ya fue inicializado con contenido diferente.", null);
    }

    @ExceptionHandler(ReservaConflictException.class)
    public ResponseEntity<ErrorResponse> conflictoReserva(ReservaConflictException exception) {
        return respuesta(HttpStatus.CONFLICT, exception.getMessage(), null);
    }

    @ExceptionHandler(DisponibilidadNoRegistradaException.class)
    public ResponseEntity<ErrorResponse> disponibilidadNoRegistrada(DisponibilidadNoRegistradaException exception) {
        return respuesta(HttpStatus.NOT_FOUND,
                "No hay disponibilidad registrada en MS2 para el evento solicitado.", null);
    }

    @ExceptionHandler(ReservaNoEncontradaException.class)
    public ResponseEntity<ErrorResponse> reservaNoEncontrada(ReservaNoEncontradaException exception) {
        return respuesta(HttpStatus.NOT_FOUND,
                "No existe una reserva registrada en MS2 con ese identificador.", null);
    }

    @ExceptionHandler(ReservaVencidaException.class)
    public ResponseEntity<ErrorResponse> reservaVencida(ReservaVencidaException exception) {
        return respuesta(HttpStatus.CONFLICT, "RESERVA_VENCIDA",
                "La reserva venció antes de ser confirmada.", null);
    }

    @ExceptionHandler(ReservaLiberadaException.class)
    public ResponseEntity<ErrorResponse> reservaLiberada(ReservaLiberadaException exception) {
        return respuesta(HttpStatus.CONFLICT, "RESERVA_LIBERADA",
                "La reserva ya fue liberada.", null);
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, ConstraintViolationException.class })
    public ResponseEntity<ErrorResponse> validacion(Exception exception) {
        List<CampoError> campos = switch (exception) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getFieldErrors().stream()
                    .map(error -> new CampoError(error.getField(), error.getDefaultMessage()))
                    .distinct()
                    .toList();
            case ConstraintViolationException invalid -> invalid.getConstraintViolations().stream()
                    .map(violation -> new CampoError(violation.getPropertyPath().toString(), violation.getMessage()))
                    .distinct()
                    .toList();
            default -> List.of();
        };
        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud contiene datos inválidos.", campos);
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, IllegalArgumentException.class,
            MissingRequestHeaderException.class })
    public ResponseEntity<ErrorResponse> solicitudInvalida(Exception exception) {
        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud contiene datos inválidos.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> tipoContenidoNoAdmitido(HttpMediaTypeNotSupportedException exception) {
        return respuesta(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "El tipo de contenido no está soportado.", null);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> errorInterno(RuntimeException exception) {
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno.", null);
    }

    private ResponseEntity<ErrorResponse> respuesta(HttpStatus status, String mensaje, List<CampoError> campos) {
        return respuesta(status, status.getReasonPhrase(), mensaje, campos);
    }

    private ResponseEntity<ErrorResponse> respuesta(
            HttpStatus status, String codigo, String mensaje, List<CampoError> campos) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), codigo, mensaje, campos);
        return ResponseEntity.status(status).body(body);
    }
}
