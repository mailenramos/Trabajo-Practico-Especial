package ticketya.dobles;

import ticketya.cliente.PagosCliente;
import ticketya.cliente.ServicioExternoException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Doble de prueba de Pagos que imita las reglas del contrato: misma clave y mismos datos
 * devuelven el pago original; misma clave con datos distintos o clave nueva sobre una
 * compra con pago aprobado dan 409; DIGITAL_WALLET siempre se rechaza; la consulta por
 * compra devuelve una lista (vacía si no hay pagos).
 */
public class PagosFalso implements PagosCliente {

    private final Map<String, Pago> porClave = new ConcurrentHashMap<>();
    private final Map<String, List<Pago>> porCompra = new ConcurrentHashMap<>();
    private final Map<String, Reintegro> reintegros = new ConcurrentHashMap<>();
    private final AtomicInteger cobrosNuevos = new AtomicInteger();
    private final AtomicInteger pedidosDeCobro = new AtomicInteger();
    private final AtomicInteger consultasPorCompra = new AtomicInteger();

    private volatile EstadoPago decision = EstadoPago.APROBADO;
    /** Registra el cobro pero simula que la respuesta no llega (corte de conexión). */
    private volatile boolean perderRespuesta;
    /** No registra nada y falla: el pedido nunca llegó a Pagos. */
    private volatile boolean caido;
    private volatile long demoraMs;

    public void setDecision(EstadoPago decision) { this.decision = decision; }
    public void setPerderRespuesta(boolean perder) { this.perderRespuesta = perder; }
    public void setCaido(boolean caido) { this.caido = caido; }
    public void setDemoraMs(long demoraMs) { this.demoraMs = demoraMs; }

    public int cobrosNuevos() { return cobrosNuevos.get(); }
    public int pedidosDeCobro() { return pedidosDeCobro.get(); }
    public int consultasPorCompra() { return consultasPorCompra.get(); }

    /** Registra un pago de la compra hecho con otra clave (por ejemplo, desde otro pedido). */
    public synchronized Pago registrarPago(String purchaseId, BigDecimal amount, EstadoPago status) {
        Pago pago = new Pago(UUID.randomUUID().toString(), purchaseId, amount, status, "CREDIT_CARD");
        porClave.put("externa-" + pago.paymentId(), pago);
        porCompra.computeIfAbsent(purchaseId, id -> new CopyOnWriteArrayList<>()).add(pago);
        return pago;
    }

    /** El cobro registrado con esa Idempotency-Key, si lo hay. */
    public Optional<Pago> pagoConClave(String idempotencyKey) {
        return Optional.ofNullable(porClave.get(idempotencyKey));
    }

    public void reiniciar() {
        porClave.clear();
        porCompra.clear();
        reintegros.clear();
        clavePorReintegro.clear();
        pedidosDeReintegro.set(0);
        reintegrosNuevos.set(0);
        reintentosDeReintegro.set(0);
        consultasDeReintegro.set(0);
        resultadoReintegro = EstadoReintegroPagos.EXITOSO;
        resultadoReintento = EstadoReintegroPagos.EXITOSO;
        perderRespuestaReintegro = false;
        reintegrosCaidos = false;
        reintentoYaHecho = false;
        reintegrosSinRespuesta.set(0);
        cobrosNuevos.set(0);
        pedidosDeCobro.set(0);
        consultasPorCompra.set(0);
        decision = EstadoPago.APROBADO;
        perderRespuesta = false;
        caido = false;
        demoraMs = 0;
    }

    @Override
    public Pago cobrar(String idempotencyKey, String purchaseId, BigDecimal amount, String paymentMethod) {
        Objects.requireNonNull(idempotencyKey, "Pagos exige Idempotency-Key");
        pedidosDeCobro.incrementAndGet();
        esperar();
        if (caido) {
            throw new ServicioExternoException("Pagos", "Pagos no responde (doble)");
        }
        Pago pago;
        synchronized (this) {
            Pago anterior = porClave.get(idempotencyKey);
            if (anterior != null) {
                if (!anterior.purchaseId().equals(purchaseId) || anterior.amount().compareTo(amount) != 0
                        || !anterior.paymentMethod().equals(paymentMethod)) {
                    throw new ConflictoEnPagosException("Misma clave con datos distintos");
                }
                pago = anterior;
            } else {
                boolean yaAprobada = porCompra.getOrDefault(purchaseId, List.of()).stream()
                        .anyMatch(p -> p.status() == EstadoPago.APROBADO);
                if (yaAprobada) {
                    throw new ConflictoEnPagosException("La compra ya tiene un pago aprobado");
                }
                EstadoPago estado = "DIGITAL_WALLET".equals(paymentMethod) ? EstadoPago.RECHAZADO : decision;
                pago = new Pago(UUID.randomUUID().toString(), purchaseId, amount, estado, paymentMethod);
                porClave.put(idempotencyKey, pago);
                porCompra.computeIfAbsent(purchaseId, id -> new CopyOnWriteArrayList<>()).add(pago);
                cobrosNuevos.incrementAndGet();
            }
        }
        if (perderRespuesta) {
            throw new ServicioExternoException("Pagos", "Se cortó la conexión antes de la respuesta (doble)");
        }
        return pago;
    }

    @Override
    public List<Pago> listarPagosPorCompra(String purchaseId) {
        consultasPorCompra.incrementAndGet();
        if (caido) {
            throw new ServicioExternoException("Pagos", "Pagos no responde (doble)");
        }
        return List.copyOf(porCompra.getOrDefault(purchaseId, List.of()));
    }

    // ------------------------------------------------------------------ reintegros

    private final Map<String, String> clavePorReintegro = new ConcurrentHashMap<>();
    private final AtomicInteger pedidosDeReintegro = new AtomicInteger();
    private final AtomicInteger reintegrosNuevos = new AtomicInteger();
    private final AtomicInteger reintentosDeReintegro = new AtomicInteger();
    private final AtomicInteger consultasDeReintegro = new AtomicInteger();
    private volatile EstadoReintegroPagos resultadoReintegro = EstadoReintegroPagos.EXITOSO;
    private volatile EstadoReintegroPagos resultadoReintento = EstadoReintegroPagos.EXITOSO;
    private volatile boolean perderRespuestaReintegro;
    private volatile boolean reintegrosCaidos;
    /** Simula que otro pedido ya reintentó con éxito: el próximo /retry da 409. */
    private volatile boolean reintentoYaHecho;

    public void setResultadoReintegro(EstadoReintegroPagos r) { this.resultadoReintegro = r; }
    public void setResultadoReintento(EstadoReintegroPagos r) { this.resultadoReintento = r; }
    public void setPerderRespuestaReintegro(boolean v) { this.perderRespuestaReintegro = v; }
    public void setReintegrosCaidos(boolean v) { this.reintegrosCaidos = v; }
    public void setReintentoYaHecho(boolean v) { this.reintentoYaHecho = v; }

    /** Los próximos {@code n} pedidos de reintegro no responden (y Pagos no registra nada). */
    private final AtomicInteger reintegrosSinRespuesta = new AtomicInteger();
    public void setProximosReintegrosSinRespuesta(int n) { reintegrosSinRespuesta.set(n); }

    public int pedidosDeReintegro() { return pedidosDeReintegro.get(); }
    public int reintegrosNuevos() { return reintegrosNuevos.get(); }
    public int reintentosDeReintegro() { return reintentosDeReintegro.get(); }
    public int consultasDeReintegro() { return consultasDeReintegro.get(); }

    public Optional<Reintegro> reintegroPorSolicitud(String refundRequestId) {
        return Optional.ofNullable(reintegros.get(refundRequestId));
    }

    /**
     * Imita el contrato: reason obligatorio (400), misma clave devuelve el original, el mismo
     * refundRequestId con otra clave da 409, solo sobre pagos aprobados y sin superar lo
     * cobrado (los fallidos no descuentan).
     */
    @Override
    public synchronized Reintegro reintegrar(String paymentId, String idempotencyKey, String refundRequestId,
                                             BigDecimal amount, String reason) {
        pedidosDeReintegro.incrementAndGet();
        if (reintegrosCaidos || reintegrosSinRespuesta.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ServicioExternoException("Pagos", "Pagos no responde (doble)");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServicioExternoException("Pagos", "Pagos respondió 400: reason es obligatorio (doble)");
        }
        Reintegro existente = reintegros.get(refundRequestId);
        if (existente != null) {
            if (!idempotencyKey.equals(clavePorReintegro.get(refundRequestId))) {
                throw new ConflictoEnPagosException("refundRequestId usado con otra clave");
            }
            return existente;
        }
        Pago pago = porClave.values().stream().filter(p -> p.paymentId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new ConflictoEnPagosException("Pago inexistente"));
        if (pago.status() != EstadoPago.APROBADO) {
            throw new ConflictoEnPagosException("Solo se reintegra sobre pagos aprobados");
        }
        BigDecimal reintegrado = reintegros.values().stream()
                .filter(r -> r.paymentId().equals(paymentId) && r.status() == EstadoReintegroPagos.EXITOSO)
                .map(Reintegro::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reintegrado.add(amount).compareTo(pago.amount()) > 0) {
            throw new ConflictoEnPagosException("La suma supera lo cobrado");
        }
        Reintegro nuevo = new Reintegro(UUID.randomUUID().toString(), paymentId, refundRequestId, amount,
                resultadoReintegro, reason, 1, Instant.now(), Instant.now());
        reintegros.put(refundRequestId, nuevo);
        clavePorReintegro.put(refundRequestId, idempotencyKey);
        reintegrosNuevos.incrementAndGet();
        if (perderRespuestaReintegro) {
            throw new ServicioExternoException("Pagos", "Se cortó la conexión antes de la respuesta (doble)");
        }
        return nuevo;
    }

    /** Solo sobre un reintegro FALLIDO (si no, 409). Suma un intento. */
    @Override
    public synchronized Reintegro reintentarReintegro(String refundId) {
        reintentosDeReintegro.incrementAndGet();
        if (reintegrosCaidos) {
            throw new ServicioExternoException("Pagos", "Pagos no responde (doble)");
        }
        Reintegro r = reintegros.values().stream().filter(x -> x.id().equals(refundId)).findFirst()
                .orElseThrow(() -> new ConflictoEnPagosException("Reintegro inexistente"));
        if (reintentoYaHecho) {
            reintentoYaHecho = false;
            r = cambiar(r, EstadoReintegroPagos.EXITOSO);
            throw new ConflictoEnPagosException("El reintegro ya no está fallido");
        }
        if (r.status() != EstadoReintegroPagos.FALLIDO) {
            throw new ConflictoEnPagosException("El reintegro ya no está fallido");
        }
        return cambiar(r, resultadoReintento);
    }

    @Override
    public Optional<Reintegro> consultarReintegro(String refundId) {
        consultasDeReintegro.incrementAndGet();
        return reintegros.values().stream().filter(r -> r.id().equals(refundId)).findFirst();
    }

    private Reintegro cambiar(Reintegro r, EstadoReintegroPagos estado) {
        Reintegro nuevo = new Reintegro(r.id(), r.paymentId(), r.refundRequestId(), r.amount(), estado,
                r.reason(), r.attemptCount() + 1, r.createdAt(), Instant.now());
        reintegros.put(r.refundRequestId(), nuevo);
        return nuevo;
    }

    private void esperar() {
        if (demoraMs > 0) {
            try {
                Thread.sleep(demoraMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
