package com.ticketya.gestionlugares.api;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ticketya.gestionlugares.service.ConflictException;
import com.ticketya.gestionlugares.service.ForbiddenException;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.ServiceUnavailableException;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> manejarValidacion(MethodArgumentNotValidException exception) {
        List<ApiFieldError> campos = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), error.getDefaultMessage()))
                .distinct().toList();
        return error(HttpStatus.BAD_REQUEST, "Solicitud inválida", campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> manejarJsonInvalido(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "El cuerpo JSON está ausente o mal formado", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> manejarArgumentoInvalido(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> manejarNoAutenticado(UnauthorizedException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> manejarProhibido(ForbiddenException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage(), null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> manejarNoEncontrado(ResourceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), null);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> manejarServicioExternoNoDisponible(ServiceUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), null);
    }

    @ExceptionHandler({ConflictException.class, IllegalStateException.class,
            DuplicateKeyException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> manejarConflicto(Exception exception) {
        String mensaje = exception.getMessage() == null ? "Conflicto con el estado actual del recurso" : exception.getMessage();
        return error(HttpStatus.CONFLICT, mensaje, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> manejarMetodoNoSoportado(HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Método HTTP no soportado", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> manejarErrorInterno(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servicio", null);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String mensaje, List<ApiFieldError> campos) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), mensaje,
                        campos == null || campos.isEmpty() ? null : campos));
    }
}
