package ticketya.dobles;

import ticketya.cliente.EntradasCliente;
import ticketya.cliente.EstadoEntradaConflictoException;
import ticketya.cliente.ServicioExternoException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Doble de prueba de Entradas: idempotente por idCompra, con fallas configurables. */
public class EntradasFalso implements EntradasCliente {

    private final Map<String, List<EntradaEmitida>> emitidas = new ConcurrentHashMap<>();
    private final AtomicInteger llamadas = new AtomicInteger();
    private final AtomicInteger fallasPendientes = new AtomicInteger();

    /** Las próximas {@code n} llamadas fallan. */
    public void fallarProximas(int n) {
        fallasPendientes.set(n);
    }

    public int llamadas() { return llamadas.get(); }

    public int emisiones() { return emitidas.size(); }

    /** Pedidos de emisión recibidos, en orden (incluidos los que fallaron). */
    private final List<SolicitudEmision> pedidos = new java.util.concurrent.CopyOnWriteArrayList<>();

    public List<SolicitudEmision> pedidosDeEmision() { return List.copyOf(pedidos); }

    public void reiniciar() {
        eventoPorEntrada.clear();
        marcaciones.set(0);
        marcacionCaida = false;
        entradasPorId.clear();
        pasesAEnReintegro.set(0);
        pasesADevuelta.set(0);
        caido = false;
        demoraEmisionMs = 0;
        transicionesRechazadas.clear();
        filaNoNumerica = false;
        emitidas.clear();
        pedidos.clear();
        llamadas.set(0);
        fallasPendientes.set(0);
    }

    // ------------------------------------------------------------------ estados (devoluciones)

    private final Map<String, Entrada> entradasPorId = new ConcurrentHashMap<>();
    private final AtomicInteger pasesAEnReintegro = new AtomicInteger();
    private final AtomicInteger pasesADevuelta = new AtomicInteger();
    private volatile boolean caido;

    /** Registra una entrada con su titular, estado y fecha del evento. */
    public void agregar(String idEntrada, String idCliente, EstadoEntrada estado, Instant comienzaEn) {
        entradasPorId.put(idEntrada, new Entrada(idEntrada, idCliente, estado, comienzaEn));
    }

    /** Registra una entrada con todos los datos que devuelve la consulta de Entradas. */
    public void agregar(Entrada entrada) {
        entradasPorId.put(entrada.idEntrada(), entrada);
    }

    /** Entradas deja de conocer la entrada: la consulta responde que no existe (404 ENTRADA_NO_ENCONTRADA). */
    public void olvidar(String idEntrada) {
        entradasPorId.remove(idEntrada);
    }

    public Optional<Entrada> entrada(String idEntrada) {
        return Optional.ofNullable(entradasPorId.get(idEntrada));
    }

    public EstadoEntrada estado(String idEntrada) {
        return entradasPorId.get(idEntrada).estado();
    }

    public void setCaido(boolean caido) { this.caido = caido; }

    /** Demora de cada emisión: sostiene el bloqueo de la compra durante un pago (pruebas de concurrencia). */
    private volatile long demoraEmisionMs;

    public void setDemoraEmisionMs(long ms) { this.demoraEmisionMs = ms; }

    /** Entradas responde 409 TRANSICION_INVALIDA a los cambios de estado de estas entradas. */
    private final java.util.Set<String> transicionesRechazadas = ConcurrentHashMap.newKeySet();

    public void rechazarTransicion(String idEntrada) { transicionesRechazadas.add(idEntrada); }

    public void aceptarTransicion(String idEntrada) { transicionesRechazadas.remove(idEntrada); }

    private void verificarTransicion(String idEntrada, String operacion) {
        if (transicionesRechazadas.contains(idEntrada)) {
            throw new EstadoEntradaConflictoException(idEntrada, operacion, "TRANSICION_INVALIDA", null);
        }
    }

    /** La emisión responde una fila no numérica (como lo informa el cliente REST): error de contrato. */
    private volatile boolean filaNoNumerica;

    public void setFilaNoNumerica(boolean v) { this.filaNoNumerica = v; }
    public int pasesAEnReintegro() { return pasesAEnReintegro.get(); }
    public int pasesADevuelta() { return pasesADevuelta.get(); }

    @Override
    public Optional<Entrada> consultar(String idEntrada) {
        verificarDisponible();
        return java.util.Optional.ofNullable(entradasPorId.get(idEntrada));
    }

    /** Repetible: una entrada ya en reintegro o devuelta no cambia. */
    @Override
    public void pasarAEnReintegro(String idEntrada) {
        verificarDisponible();
        pasesAEnReintegro.incrementAndGet();
        verificarTransicion(idEntrada, "pasar a en reintegro");
        entradasPorId.computeIfPresent(idEntrada, (id, e) -> e.estado() == EstadoEntrada.VIGENTE
                ? e.conEstado(EstadoEntrada.EN_REINTEGRO) : e);
    }

    @Override
    public void pasarADevuelta(String idEntrada) {
        verificarDisponible();
        pasesADevuelta.incrementAndGet();
        verificarTransicion(idEntrada, "pasar a devuelta");
        entradasPorId.computeIfPresent(idEntrada,
                (id, e) -> e.conEstado(EstadoEntrada.DEVUELTA));
    }

    // ------------------------------------------------------------------ marcación por evento

    private final Map<String, String> eventoPorEntrada = new ConcurrentHashMap<>();
    private final AtomicInteger marcaciones = new AtomicInteger();
    private volatile boolean marcacionCaida;

    /** Solo la marcación por evento no responde; el resto de Entradas funciona. */
    public void setMarcacionCaida(boolean v) { this.marcacionCaida = v; }

    public int marcaciones() { return marcaciones.get(); }

    /** Repetible: marca solo las vigentes del evento, así que la segunda vez responde 0. */
    @Override
    public int marcarEntradasDelEvento(String idEvento) {
        verificarDisponible();
        marcaciones.incrementAndGet();
        if (marcacionCaida) {
            throw new ServicioExternoException("Entradas", "Entradas no responde a la marcación (doble)");
        }
        int marcadas = 0;
        for (var e : eventoPorEntrada.entrySet()) {
            if (e.getValue().equals(idEvento)) {
                Entrada actual = entradasPorId.get(e.getKey());
                if (actual != null && actual.estado() == EstadoEntrada.VIGENTE) {
                    entradasPorId.put(actual.idEntrada(), actual.conEstado(EstadoEntrada.EN_REINTEGRO));
                    marcadas++;
                }
            }
        }
        return marcadas;
    }

    private void verificarDisponible() {
        if (caido) {
            throw new ServicioExternoException("Entradas", "Entradas no responde (doble)");
        }
    }

    // ------------------------------------------------------------------ emisión

    @Override
    public List<EntradaEmitida> emitir(SolicitudEmision solicitud) {
        llamadas.incrementAndGet();
        pedidos.add(solicitud);
        if (demoraEmisionMs > 0) {
            try {
                Thread.sleep(demoraEmisionMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fallasPendientes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ServicioExternoException("Entradas", "Entradas no responde (doble)");
        }
        if (filaNoNumerica) {
            throw new ticketya.cliente.ContratoInvalidoException("Entradas",
                    "Entradas devolvió fila = \"A\", que no es un número entero (doble)");
        }
        return emitidas.computeIfAbsent(solicitud.idCompra(), id -> {
            List<EntradaEmitida> nuevas = new java.util.ArrayList<>();
            // Como el servicio real: cada entrada emitida queda vigente a nombre del cliente,
            // con el sector anidado (id y nombre), la fila y el número del lugar pedido
            for (LineaEmision l : solicitud.lugares()) {
                EntradaEmitida e = new EntradaEmitida(UUID.randomUUID().toString(), l.idSector(), l.fila(),
                        l.numero(), "QR-" + UUID.randomUUID());
                nuevas.add(e);
                agregar(new Entrada(e.idEntrada(), solicitud.idCliente(), solicitud.idCompra(), EstadoEntrada.VIGENTE,
                        solicitud.evento().id(), solicitud.evento().comienzaEn(), l.idSector(), l.nombreSector(),
                        l.fila(), l.numero(), e.codigoQr()));
                eventoPorEntrada.put(e.idEntrada(), solicitud.evento().id());
            }
            return List.copyOf(nuevas);
        });
    }
}
