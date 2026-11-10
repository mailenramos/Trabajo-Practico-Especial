package ticketya.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EntradasCliente.EntradaEmitida;
import ticketya.cliente.EntradasCliente.EventoEmision;
import ticketya.cliente.EntradasCliente.LineaEmision;
import ticketya.cliente.EntradasCliente.SolicitudEmision;
import ticketya.cliente.PagosCliente;
import ticketya.cliente.PagosCliente.ConflictoEnPagosException;
import ticketya.cliente.PagosCliente.EstadoPago;
import ticketya.cliente.PagosCliente.Pago;
import ticketya.cliente.ReservasCliente;
import ticketya.cliente.ReservasCliente.ConfirmacionRechazadaException;
import ticketya.cliente.ReservasCliente.Reserva;
import ticketya.cliente.ServicioExternoException;
import ticketya.error.ApiException;
import ticketya.error.ErrorRespuesta;
import ticketya.modelo.ClaveIdempotencia;
import ticketya.modelo.ClaveIdempotenciaId;
import ticketya.modelo.Compra;
import ticketya.modelo.DetalleCompra;
import ticketya.modelo.EstadoClave;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.OrigenReintegro;
import ticketya.modelo.Reintegro;
import ticketya.repositorio.ClaveIdempotenciaRepository;
import ticketya.repositorio.CompraRepository;
import ticketya.web.UsuarioActual;
import ticketya.web.dto.CompraRespuesta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pago de una compra (C-2 a C-5). Orden: verificar la reserva, cobrar, guardar el pagoId,
 * confirmar la venta, emitir las entradas y dejar la compra confirmada. Ante un rechazo,
 * la compra queda fallida y se liberan los lugares.
 *
 * Idempotencia: la clave del cliente se acota por cliente. Un pedido repetido con la
 * misma clave recibe el resultado original o, si la operación quedó a medias, la retoma
 * desde el último paso hecho sin volver a cobrar.
 */
@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);
    private static final int LARGO_MAXIMO_CLAVE = 100;

    /** Los cuatro medios que admite Pagos. */
    public enum MedioDePago { CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER, DIGITAL_WALLET }

    /** Respuesta HTTP del pago: código y cuerpo JSON (se guarda tal cual para repetirla). */
    public record ResultadoPago(int status, String cuerpoJson) {
    }

    /** Cuerpo del 202 cuando otro pedido con la misma clave se está procesando. */
    public record OperacionEnCurso(UUID compraId, String estado, String mensaje) {
    }

    private final CompraRepository compras;
    private final ClaveIdempotenciaRepository claves;
    private final ReservasCliente reservas;
    private final PagosCliente pagos;
    private final EntradasCliente entradas;
    private final TransactionTemplate transaccion;
    private final JsonMapper json;
    private final int intentosEmision;
    private final Duration esperaEntreIntentos;
    private final Duration plazoEnProceso;
    private final ReintegroService reintegroService;

    public PagoService(CompraRepository compras, ClaveIdempotenciaRepository claves,
                       ReservasCliente reservas, PagosCliente pagos, EntradasCliente entradas,
                       ReintegroService reintegroService,
                       TransactionTemplate transaccion, JsonMapper json,
                       @Value("${compras.emision.intentos:3}") int intentosEmision,
                       @Value("${compras.emision.espera-entre-intentos:200ms}") Duration esperaEntreIntentos,
                       @Value("${compras.idempotencia.plazo-en-proceso:60s}") Duration plazoEnProceso) {
        this.compras = compras;
        this.claves = claves;
        this.reservas = reservas;
        this.pagos = pagos;
        this.entradas = entradas;
        this.transaccion = transaccion;
        this.json = json;
        this.intentosEmision = intentosEmision;
        this.esperaEntreIntentos = esperaEntreIntentos;
        this.plazoEnProceso = plazoEnProceso;
        this.reintegroService = reintegroService;
    }

    // ------------------------------------------------------------------ pedido de pago

    public ResultadoPago pagar(UsuarioActual usuario, UUID compraId, String clave, String medioDePago) {
        if (clave == null || clave.isBlank() || clave.length() > LARGO_MAXIMO_CLAVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLAVE_IDEMPOTENCIA_INVALIDA",
                    "El encabezado Idempotency-Key es obligatorio y tiene como máximo "
                            + LARGO_MAXIMO_CLAVE + " caracteres");
        }
        String medio = validarMedioDePago(medioDePago);
        Compra compra = compras.findById(compraId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMPRA_NO_ENCONTRADA",
                        "No existe la compra " + compraId));
        if (!compra.getClienteId().equals(usuario.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO",
                    "La compra " + compraId + " pertenece a otro cliente");
        }

        ClaveIdempotenciaId idClave = new ClaveIdempotenciaId(usuario.id(), clave);
        String huella = huella(compraId, medio);
        Toma toma = tomarClave(idClave, compraId, huella);
        if (toma.respuesta() != null) {
            return toma.respuesta();
        }

        try {
            ResultadoPago resultado = procesar(compraId, medio, toma.claveCobro(), toma.posibleCobroPrevio());
            claves.terminar(idClave, EstadoClave.COMPLETADA, guardar(resultado));
            return resultado;
        } catch (OperacionInterrumpida e) {
            claves.terminar(idClave, EstadoClave.INTERRUMPIDA, null);
            return e.respuesta;
        } catch (RuntimeException e) {
            // Falla técnica: la operación queda a medias y se puede retomar con la misma clave
            claves.terminar(idClave, EstadoClave.INTERRUMPIDA, null);
            throw e;
        }
    }

    /** Valida el medio antes de cualquier llamada a Pagos. */
    private String validarMedioDePago(String medio) {
        try {
            return MedioDePago.valueOf(medio).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIO_DE_PAGO_INVALIDO",
                    "El medio de pago debe ser CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER o DIGITAL_WALLET");
        }
    }

    // ------------------------------------------------------------------ idempotencia

    private record Toma(ResultadoPago respuesta, String claveCobro, boolean posibleCobroPrevio) {
    }

    /**
     * Registra la clave o decide qué hacer con una ya usada: misma clave con datos
     * distintos da 409; terminada devuelve el resultado original; en proceso da 202;
     * interrumpida (o en proceso hace demasiado, por una caída) se retoma.
     */
    private Toma tomarClave(ClaveIdempotenciaId id, UUID compraId, String huella) {
        try {
            ClaveIdempotencia nueva = claves.saveAndFlush(
                    new ClaveIdempotencia(id.getClienteId(), id.getClave(), compraId, huella));
            // Si otra clave ya intentó pagar esta compra, el cobro pudo haberse hecho
            boolean otraClaveIntento = claves.countByCompraId(compraId) > 1;
            return new Toma(null, nueva.getClaveCobro(), otraClaveIntento);
        } catch (DataIntegrityViolationException yaExiste) {
            ClaveIdempotencia existente = claves.findById(id).orElseThrow(() -> yaExiste);
            if (!existente.getHuellaPedido().equals(huella)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCIA_CONFLICTO",
                        "La Idempotency-Key ya se usó con otros datos de pago");
            }
            boolean retomable = switch (existente.getEstado()) {
                case COMPLETADA -> false;
                case INTERRUMPIDA -> true;
                case EN_PROCESO -> existente.getTomadaEn().plus(plazoEnProceso).isBefore(Instant.now());
            };
            if (existente.getEstado() == EstadoClave.COMPLETADA) {
                return new Toma(repetir(existente.getResultado()), null, false);
            }
            if (retomable && claves.retomar(id, existente.getEstado(), existente.getTomadaEn(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS)) == 1) {
                return new Toma(null, existente.getClaveCobro(), true);
            }
            return new Toma(responder(202, new OperacionEnCurso(compraId, "EN_CURSO",
                    "El pago con esta Idempotency-Key se está procesando. Consulte la compra en unos segundos")),
                    null, false);
        }
    }

    // ------------------------------------------------------------------ flujo del pago

    /** Corta el flujo dejando la operación retomable, con una respuesta para el cliente. */
    private static class OperacionInterrumpida extends RuntimeException {
        final ResultadoPago respuesta;

        OperacionInterrumpida(ResultadoPago respuesta) {
            super(null, null, false, false);
            this.respuesta = respuesta;
        }
    }

    /**
     * Ejecuta los pasos que falten con la fila de la compra bloqueada, así dos pagos de
     * la misma compra no avanzan a la vez. Lo hecho antes de una falla técnica queda
     * guardado (por ejemplo, el pagoId) y la falla se informa después de confirmar.
     */
    private ResultadoPago procesar(UUID compraId, String medio, String claveCobro, boolean posibleCobroPrevio) {
        RuntimeException[] falla = new RuntimeException[1];
        ResultadoPago resultado = transaccion.execute(estado -> {
            try {
                return pasos(compraId, medio, claveCobro, posibleCobroPrevio);
            } catch (ServicioExternoException | OperacionInterrumpida e) {
                falla[0] = e;
                return null;
            }
        });
        if (falla[0] != null) {
            throw falla[0];
        }
        return resultado;
    }

    private ResultadoPago pasos(UUID compraId, String medio, String claveCobro, boolean posibleCobroPrevio) {
        Compra compra = compras.bloquear(compraId).orElseThrow();
        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            // Otro pedido ya resolvió la compra: no se cobra ni se emite de nuevo
            return respuestaCompra(200, compraId);
        }

        // 1. Cobro, salvo que la compra ya tenga un pago aprobado
        if (compra.getPagoId() == null) {
            // Si el cobro pudo haberse hecho, solo cuenta un pago aprobado. Sin aprobado (lista
            // vacía o solo rechazados) se repite el cobro con la misma claveCobro: si antes fue
            // rechazado, Pagos devuelve ese rechazo original
            Pago pago = posibleCobroPrevio ? pagoAprobado(compraId.toString()).orElse(null) : null;
            if (pago == null) {
                try {
                    verificarReservaAntesDeCobrar(compra);
                } catch (ApiException e) {
                    return responderError(e);
                }
                pago = cobrar(claveCobro, compra, medio);
            }
            compra.setMedioDePago(pago.paymentMethod() != null ? pago.paymentMethod() : medio);
            if (pago.status() == EstadoPago.RECHAZADO) {
                return rechazar(compra);
            }
            if (pago.status() != EstadoPago.APROBADO) {
                throw new ServicioExternoException("Pagos", "Estado de pago inesperado: " + pago.status());
            }
            compra.setPagoId(pago.paymentId());
            compras.saveAndFlush(compra);
        }

        // 2. Confirmación de la venta (la validación definitiva de la reserva)
        if (!compra.isVentaConfirmada()) {
            try {
                reservas.confirmarVenta(compra.getReservaId());
            } catch (ConfirmacionRechazadaException e) {
                return ventaRechazada(compra, e.getCodigo());
            }
            compra.setVentaConfirmada(true);
            compras.saveAndFlush(compra);
        }

        // 3. Emisión de las entradas, con reintentos
        if (!emitirEntradas(compra)) {
            compras.saveAndFlush(compra);
            throw new OperacionInterrumpida(respuestaCompra(202, compraId));
        }
        compras.saveAndFlush(compra);

        // 4. Recién con las entradas emitidas, la compra pasa a confirmada. Solo si sigue
        // pendiente: una compra cerrada (por ejemplo, por la cancelación del evento) no se reabre
        if (compras.cambiarEstado(compraId, EstadoCompra.PENDIENTE, EstadoCompra.CONFIRMADA) == 0) {
            log.warn("La compra {} dejó de estar pendiente antes de confirmarla: no se confirma", compraId);
        }
        return respuestaCompra(200, compraId);
    }

    /**
     * C-2: verificación preventiva antes de pedir el cobro. Si la reserva venció o se
     * liberó no se cobra y el cliente tiene que reservar de nuevo. La validación
     * definitiva es la confirmación de la venta.
     */
    void verificarReservaAntesDeCobrar(Compra compra) {
        Reserva reserva = reservas.obtenerReserva(compra.getReservaId()).orElse(null);
        if (reserva == null || !reserva.estaVigente(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVA_NO_VIGENTE",
                    "La reserva " + compra.getReservaId()
                            + " venció o fue liberada. No se realizó el cobro: hay que reservar de nuevo");
        }
    }

    /**
     * Pide el cobro con la claveCobro. Si Pagos responde 409 (por ejemplo, la compra ya
     * tiene un pago aprobado con otra clave), se usa el pago aprobado de la compra.
     */
    private Pago cobrar(String claveCobro, Compra compra, String medio) {
        String purchaseId = compra.getId().toString();
        try {
            return pagos.cobrar(claveCobro, purchaseId, compra.getTotal(), medio);
        } catch (ConflictoEnPagosException e) {
            return pagoAprobado(purchaseId)
                    .orElseThrow(() -> new ServicioExternoException("Pagos",
                            "Pagos rechazó el cobro por conflicto y no informa un pago aprobado de la compra", e));
        }
    }

    /** El pago aprobado de la compra, si Pagos tiene uno. Los rechazados no cuentan. */
    private Optional<Pago> pagoAprobado(String purchaseId) {
        return pagos.listarPagosPorCompra(purchaseId).stream()
                .filter(p -> p.status() == EstadoPago.APROBADO)
                .findFirst();
    }

    /**
     * C-3: el pago se aprobó pero Reservas rechazó la venta (la reserva venció o se liberó).
     * La compra queda fallida y se pide un reintegro automático por el total. El reintegro
     * se crea solo si este pedido hizo el cambio a fallida, así nunca hay dos por compra.
     * Si el reintegro falla o no tiene respuesta, la compra queda fallida igual y el
     * reintegro queda registrado para reintentarlo. No se libera la reserva: ya no está.
     */
    private ResultadoPago ventaRechazada(Compra compra, String codigo) {
        UUID compraId = compra.getId();
        String pagoId = compra.getPagoId();
        log.warn("Compra {} con pago {} aprobado pero venta rechazada ({}): reintegro automático",
                compraId, pagoId, codigo);
        compras.saveAndFlush(compra);
        if (compras.cambiarEstado(compraId, EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA) == 1) {
            Reintegro reintegro = reintegroService.registrar(compraId, UUID.randomUUID(), compra.getTotal(),
                    "Reserva vencida o liberada antes de confirmar la venta", OrigenReintegro.VENTA_RECHAZADA);
            reintegroService.pedir(reintegro, pagoId);
        }
        return respuestaCompra(200, compraId);
    }

    /** C-4: pago rechazado. La compra queda fallida y se liberan los lugares. */
    private ResultadoPago rechazar(Compra compra) {
        compras.saveAndFlush(compra);
        compras.cambiarEstado(compra.getId(), EstadoCompra.PENDIENTE, EstadoCompra.FALLIDA);
        try {
            reservas.liberarPorPagoRechazado(compra.getReservaId());
        } catch (ServicioExternoException e) {
            // La reserva igual vence sola; la compra ya quedó fallida
            log.warn("No se pudo liberar la reserva {} de la compra fallida {}: {}",
                    compra.getReservaId(), compra.getId(), e.getMessage());
        }
        return respuestaCompra(200, compra.getId());
    }

    /**
     * Pide la emisión hasta {@code intentosEmision} veces y guarda el entradaId de cada
     * lugar. Entradas es idempotente por idCompra, así que repetir es seguro.
     */
    private boolean emitirEntradas(Compra compra) {
        if (compra.getDetalle().stream().allMatch(d -> d.getEntradaId() != null)) {
            return true;
        }
        SolicitudEmision solicitud = new SolicitudEmision(compra.getId().toString(), compra.getClienteId(),
                new EventoEmision(compra.getEventoId(), compra.getEventoNombre(), compra.getEventoFechaInicio()),
                compra.getDetalle().stream()
                        .map(d -> new LineaEmision(d.getSectorId(), d.getSectorNombre(), d.getFila(), d.getNumero()))
                        .toList());
        for (int intento = 1; intento <= intentosEmision; intento++) {
            try {
                asignarEntradas(compra, entradas.emitir(solicitud));
                return true;
            } catch (ServicioExternoException e) {
                log.warn("Emisión de entradas de la compra {} falló (intento {} de {}): {}",
                        compra.getId(), intento, intentosEmision, e.getMessage());
                if (intento < intentosEmision) {
                    esperar();
                }
            }
        }
        return false;
    }

    /** Asocia cada entrada emitida con su lugar (mismo sector, fila y número). */
    private void asignarEntradas(Compra compra, List<EntradaEmitida> emitidas) {
        List<EntradaEmitida> libres = new ArrayList<>(emitidas);
        for (DetalleCompra d : compra.getDetalle()) {
            EntradaEmitida e = libres.stream()
                    .filter(x -> Objects.equals(x.sectorId(), d.getSectorId())
                            && Objects.equals(x.fila(), d.getFila())
                            && Objects.equals(x.numero(), d.getNumero()))
                    .findFirst()
                    .orElseThrow(() -> new ServicioExternoException("Entradas",
                            "Entradas no devolvió una entrada para cada lugar comprado"));
            libres.remove(e);
            d.setEntradaId(e.idEntrada());
        }
    }

    private void esperar() {
        if (esperaEntreIntentos.isZero()) {
            return;
        }
        try {
            Thread.sleep(esperaEntreIntentos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ respuestas

    private ResultadoPago respuestaCompra(int status, UUID compraId) {
        return responder(status, CompraRespuesta.de(compras.buscarConDetalle(compraId).orElseThrow(),
                reintegroService.deLaCompra(compraId)));
    }

    private ResultadoPago responderError(ApiException e) {
        return responder(e.getStatus().value(),
                ErrorRespuesta.de(e.getStatus().value(), e.getCodigo(), e.getMessage()));
    }

    private ResultadoPago responder(int status, Object cuerpo) {
        return new ResultadoPago(status, json.writeValueAsString(cuerpo));
    }

    /**
     * Resultado original guardado con la clave, para devolverlo igual si el pedido se
     * repite. El cuerpo se guarda como texto, sin volver a interpretarlo, para que los
     * importes conserven sus decimales.
     */
    private String guardar(ResultadoPago r) {
        return json.writeValueAsString(Map.of("status", r.status(), "cuerpo", r.cuerpoJson()));
    }

    private ResultadoPago repetir(String guardado) {
        JsonNode nodo = json.readTree(guardado);
        return new ResultadoPago(nodo.get("status").asInt(), nodo.get("cuerpo").asString());
    }

    /** Resumen de los datos del pedido: misma clave con otra huella es "datos distintos". */
    private static String huella(UUID compraId, String medio) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((compraId + "|" + medio).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
