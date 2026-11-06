package com.ticketya.gestionlugares.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ticketya.gestionlugares.service.ForbiddenException;
import com.ticketya.gestionlugares.service.ResourceNotFoundException;
import com.ticketya.gestionlugares.service.UnauthorizedException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> manejarValidacion(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Solicitud inválida");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> manejarArgumentoInvalido(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> manejarNoAutenticado(UnauthorizedException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> manejarProhibido(ForbiddenException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> manejarNoEncontrado(ResourceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({DuplicateKeyException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> manejarConflicto(Exception exception) {
        return error(HttpStatus.CONFLICT, "El recurso cambió en paralelo; vuelva a intentar la operación");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
