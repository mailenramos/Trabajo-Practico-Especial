package com.ticketya.msvc.entradas.exception;

import com.ticketya.msvc.entradas.dto.ErrorResponseDTO;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDTO> noEncontrado(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ENTRADA_NO_ENCONTRADA", ex.getMessage(), null);
    }

    @ExceptionHandler(AccesoDenegadoException.class)
    public ResponseEntity<ErrorResponseDTO> accesoDenegado(AccesoDenegadoException ex) {
        return error(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<ErrorResponseDTO> conflicto(BusinessConflictException ex) {
        return error(HttpStatus.CONFLICT, ex.getCodigo(), ex.getMessage(), null);
    }

    @ExceptionHandler(ValidacionException.class)
    public ResponseEntity<ErrorResponseDTO> validacion(ValidacionException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> cuerpoInvalido(MethodArgumentNotValidException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "Datos inválidos", campos);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDTO> parametrosInvalidos(ConstraintViolationException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> campos.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "Parámetros inválidos", campos);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponseDTO> parametrosInvalidosMetodo(HandlerMethodValidationException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        for (ParameterValidationResult r : ex.getParameterValidationResults()) {
            String nombre = r.getMethodParameter().getParameterName();
            String mensaje = r.getResolvableErrors().stream()
                    .map(MessageSourceResolvable::getDefaultMessage)
                    .collect(Collectors.joining(", "));
            campos.putIfAbsent(nombre != null ? nombre : "parametro", mensaje);
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "Parámetros inválidos", campos);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponseDTO> faltaEncabezado(MissingRequestHeaderException ex) {
        return error(HttpStatus.UNAUTHORIZED, "FALTA_IDENTIFICACION",
                "Falta el encabezado " + ex.getHeaderName(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDTO> tipoIncorrecto(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION",
                "Valor inválido para el parámetro '" + ex.getName() + "'", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDTO> cuerpoIlegible(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "El cuerpo de la solicitud no es válido", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> inesperado(Exception ex) {
        log.error("Error inesperado", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno del servidor", null);
    }

    private ResponseEntity<ErrorResponseDTO> error(HttpStatus estado, String codigo,
                                                   String mensaje, Map<String, String> campos) {
        return ResponseEntity.status(estado).body(new ErrorResponseDTO(
                LocalDateTime.now(ZoneOffset.UTC),
                estado.value(),
                estado.getReasonPhrase(),
                codigo,
                mensaje,
                campos));
    }
}
