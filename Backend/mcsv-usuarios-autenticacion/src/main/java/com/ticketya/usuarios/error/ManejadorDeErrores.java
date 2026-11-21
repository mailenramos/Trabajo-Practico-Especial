package com.ticketya.usuarios.error;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorRespuesta> api(ApiException ex) {
        return error(ex.getEstado(), ex.getCodigo(), ex.getMessage(), ex.getCampos());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorRespuesta> cuerpoInvalido(MethodArgumentNotValidException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "Datos inválidos", campos);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorRespuesta> parametrosInvalidos(ConstraintViolationException ex) {
        Map<String, String> campos = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> campos.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "Parámetros inválidos", campos);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorRespuesta> parametrosInvalidosMetodo(HandlerMethodValidationException ex) {
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
    public ResponseEntity<ErrorRespuesta> faltaEncabezado(MissingRequestHeaderException ex) {
        return error(HttpStatus.UNAUTHORIZED, "FALTA_IDENTIFICACION",
                "Falta el encabezado " + ex.getHeaderName(), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorRespuesta> faltaParametro(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION",
                "Falta el parámetro " + ex.getParameterName(),
                Map.of(ex.getParameterName(), "es obligatorio"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorRespuesta> tipoIncorrecto(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION",
                "Valor inválido para el parámetro " + ex.getName(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorRespuesta> cuerpoIlegible(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDACION", "El cuerpo de la solicitud no es válido", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorRespuesta> metodoNoSoportado(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO",
                "Método no permitido para esta ruta", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorRespuesta> rutaInexistente(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "RUTA_NO_ENCONTRADA", "La ruta no existe", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorRespuesta> modificacionConcurrente(ObjectOptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "MODIFICACION_CONCURRENTE",
                "El usuario fue modificado por otro pedido; reintentá la operación", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRespuesta> inesperado(Exception ex) {
        LOG.error("Error inesperado", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Error interno del servidor", null);
    }

    private static ResponseEntity<ErrorRespuesta> error(HttpStatus estado, String codigo,
                                                        String mensaje, Map<String, String> campos) {
        return ResponseEntity.status(estado)
                .body(new ErrorRespuesta(Instant.now(), estado.value(), codigo, mensaje, campos));
    }
}
