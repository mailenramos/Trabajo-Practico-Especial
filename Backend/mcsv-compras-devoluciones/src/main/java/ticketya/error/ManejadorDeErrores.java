package ticketya.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ticketya.cliente.ContratoInvalidoException;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.ServicioExternoException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Convierte toda excepción en una respuesta con el formato de error del grupo. */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorRespuesta> api(ApiException e) {
        return responder(e.getStatus(), e.getCodigo(), e.getMessage());
    }

    @ExceptionHandler(ContratoInvalidoException.class)
    ResponseEntity<ErrorRespuesta> contratoInvalido(ContratoInvalidoException e) {
        log.warn("Respuesta de {} fuera de contrato: {}", e.getServicio(), e.getMessage());
        return responder(HttpStatus.BAD_GATEWAY, "CONTRATO_INVALIDO",
                "El servicio de " + e.getServicio() + " respondió datos con un formato inesperado");
    }

    /**
     * Entradas rechazó un cambio de estado (409 TRANSICION_INVALIDA). Hoy los pasos que cambian
     * estados lo registran y no lo propagan; si llegara hasta acá, es un conflicto y no una caída.
     */
    @ExceptionHandler(EstadoEntradaConflictoException.class)
    ResponseEntity<ErrorRespuesta> conflictoDeEstadoExterno(EstadoEntradaConflictoException e) {
        log.warn("Conflicto de estado en {}: {}", e.getServicio(), e.getMessage());
        return responder(HttpStatus.CONFLICT, "CONFLICTO_DE_ESTADO_EXTERNO",
                "El servicio de " + e.getServicio() + " rechazó el cambio de estado de la entrada "
                        + e.getIdEntrada() + " por transición inválida");
    }

    @ExceptionHandler(ServicioExternoException.class)
    ResponseEntity<ErrorRespuesta> servicioExterno(ServicioExternoException e) {
        log.warn("Falla al llamar a {}: {}", e.getServicio(), e.getMessage());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE",
                "El servicio de " + e.getServicio() + " no está disponible. Intente de nuevo más tarde");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorRespuesta> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ErrorRespuesta cuerpo = new ErrorRespuesta(Instant.now(), 400, "VALIDACION",
                "El pedido tiene datos inválidos", campos);
        return ResponseEntity.badRequest().body(cuerpo);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorRespuesta> encabezadoFaltante(MissingRequestHeaderException e) {
        return responder(HttpStatus.BAD_REQUEST, "ENCABEZADO_FALTANTE",
                "Falta el encabezado " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorRespuesta> cuerpoIlegible(HttpMessageNotReadableException e) {
        return responder(HttpStatus.BAD_REQUEST, "CUERPO_INVALIDO", "El cuerpo del pedido no es un JSON válido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorRespuesta> parametroInvalido(MethodArgumentTypeMismatchException e) {
        return responder(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO",
                "El parámetro " + e.getName() + " no tiene un formato válido");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorRespuesta> metodoNoPermitido(HttpRequestMethodNotSupportedException e) {
        return responder(HttpStatus.METHOD_NOT_ALLOWED, "METODO_NO_PERMITIDO",
                "El método " + e.getMethod() + " no está permitido en esta ruta");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorRespuesta> rutaInexistente(NoResourceFoundException e) {
        return responder(HttpStatus.NOT_FOUND, "RUTA_NO_ENCONTRADA", "La ruta pedida no existe");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorRespuesta> inesperado(Exception e) {
        log.error("Error inesperado", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_INTERNO", "Ocurrió un error inesperado");
    }

    private ResponseEntity<ErrorRespuesta> responder(HttpStatus status, String codigo, String mensaje) {
        return ResponseEntity.status(status).body(ErrorRespuesta.de(status.value(), codigo, mensaje));
    }
}
