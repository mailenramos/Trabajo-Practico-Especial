package ticketya.servicio;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ticketya.cliente.EventosCliente;
import ticketya.cliente.EventosCliente.Evento;
import ticketya.cliente.ReservasCliente;
import ticketya.cliente.ReservasCliente.Disponibilidad;
import ticketya.cliente.ReservasCliente.SectorDisponibilidad;
import ticketya.error.ApiException;
import ticketya.modelo.EstadoCompra;
import ticketya.modelo.EstadoReintegro;
import ticketya.repositorio.CantidadEImporte;
import ticketya.repositorio.CompraRepository;
import ticketya.repositorio.EntradaDeReintegroRepository;
import ticketya.repositorio.ReintegroPorSector;
import ticketya.repositorio.ReintegroRepository;
import ticketya.repositorio.ReintegrosPorEstado;
import ticketya.repositorio.SolicitudDevolucionRepository;
import ticketya.repositorio.TotalPorSector;
import ticketya.web.Permisos;
import ticketya.web.UsuarioActual;
import ticketya.web.dto.CantidadImporte;
import ticketya.web.dto.EncabezadoReporte;
import ticketya.web.dto.ReporteDevolucionesRespuesta;
import ticketya.web.dto.ReporteOcupacionRespuesta;
import ticketya.web.dto.ReporteRecaudacionRespuesta;
import ticketya.web.dto.ReporteVentasRespuesta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reportes para organizadores (ventas, devoluciones, recaudación y ocupación de un evento),
 * calculados al momento con consultas agregadas: no se guarda ningún resultado.
 * <p>
 * Primero se autoriza (rol, evento en Eventos, titularidad) y, en ocupación, se consulta a
 * Disponibilidad y reservas: esas llamadas quedan fuera de la transacción para no retener una
 * conexión mientras se espera a otro servicio. Después, todas las consultas del reporte corren en
 * una única transacción de solo lectura, así leen la misma instantánea y las cifras cierran entre sí.
 */
@Service
public class ReporteService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final EventosCliente eventos;
    private final ReservasCliente reservas;
    private final Permisos permisos;
    private final CompraRepository compras;
    private final SolicitudDevolucionRepository solicitudes;
    private final ReintegroRepository reintegros;
    private final EntradaDeReintegroRepository entradasDeReintegro;
    private final TransactionTemplate lectura;

    public ReporteService(EventosCliente eventos, ReservasCliente reservas, Permisos permisos,
                          CompraRepository compras, SolicitudDevolucionRepository solicitudes,
                          ReintegroRepository reintegros, EntradaDeReintegroRepository entradasDeReintegro,
                          PlatformTransactionManager transacciones) {
        this.eventos = eventos;
        this.reservas = reservas;
        this.permisos = permisos;
        this.compras = compras;
        this.solicitudes = solicitudes;
        this.reintegros = reintegros;
        this.entradasDeReintegro = entradasDeReintegro;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
    }

    // ------------------------------------------------------------------ autorización

    /**
     * El evento, si el usuario puede ver sus reportes: sin rol de organizador ni administrador,
     * 403 SIN_PERMISO_REPORTES sin consultar a Eventos; evento inexistente, 404
     * EVENTO_NO_ENCONTRADO; organizador que no es el titular, 403 EVENTO_AJENO. Si Eventos no
     * responde, la falla se propaga como 503.
     */
    public Evento eventoAutorizado(UsuarioActual usuario, String eventoId) {
        permisos.exigirRolDeReportes(usuario);
        Evento evento = eventos.obtenerEvento(eventoId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "EVENTO_NO_ENCONTRADO", "No existe el evento " + eventoId));
        permisos.exigirTitularidadDeEvento(usuario, evento.organizadorId());
        return evento;
    }

    /**
     * Hay operaciones pendientes si el evento tiene compras cobradas sin confirmar, devoluciones en
     * curso o reintegros sin resultado exitoso. Una compra PENDIENTE sin pago no cuenta.
     */
    @Transactional(readOnly = true)
    public boolean hayOperacionesPendientes(String eventoId) {
        return pendientes(eventoId);
    }

    private boolean pendientes(String eventoId) {
        return compras.contarCobradasSinConfirmar(eventoId) > 0
                || solicitudes.contarEnCurso(eventoId) > 0
                || reintegros.contarSinExito(eventoId) > 0;
    }

    private EncabezadoReporte encabezado(Evento evento) {
        return new EncabezadoReporte(evento.eventoId(), evento.nombre(), evento.estado(), Instant.now(),
                pendientes(evento.eventoId()));
    }

    // ------------------------------------------------------------------ ventas

    public ReporteVentasRespuesta ventas(UsuarioActual usuario, String eventoId) {
        Evento evento = eventoAutorizado(usuario, eventoId);
        return lectura.execute(tx -> {
            Map<String, TotalPorSector> vendidas = porSector(compras.ventasPorSector(eventoId));
            List<ReporteVentasRespuesta.Sector> sectores = new ArrayList<>();
            long entradas = 0;
            BigDecimal importe = BigDecimal.ZERO;
            for (Map.Entry<String, String> s : sectoresDelReporte(evento, vendidas.values()).entrySet()) {
                TotalPorSector v = vendidas.getOrDefault(s.getKey(), vacio(s.getKey()));
                sectores.add(new ReporteVentasRespuesta.Sector(s.getKey(), s.getValue(), v.cantidad(), dinero(v.importe())));
                entradas += v.cantidad();
                importe = importe.add(v.importe());
            }
            long confirmadas = compras.countByEventoIdAndEstado(eventoId, EstadoCompra.CONFIRMADA);
            return new ReporteVentasRespuesta(encabezado(evento),
                    new ReporteVentasRespuesta.Ventas(confirmadas, entradas, dinero(importe)),
                    sectores, cantidadImporte(compras.cobradasSinConfirmar(eventoId)));
        });
    }

    // ------------------------------------------------------------------ devoluciones

    public ReporteDevolucionesRespuesta devoluciones(UsuarioActual usuario, String eventoId) {
        Evento evento = eventoAutorizado(usuario, eventoId);
        return lectura.execute(tx -> {
            Map<String, TotalPorSector> vendidas = porSector(compras.ventasPorSector(eventoId));
            Map<String, TotalPorSector> porDevolucion = porSector(solicitudes.devueltasPorSector(eventoId));
            Map<String, TotalPorSector> porCancelacion = porSector(entradasDeReintegro.devueltasPorCancelacion(eventoId));

            List<TotalPorSector> todas = new ArrayList<>(vendidas.values());
            todas.addAll(porDevolucion.values());
            todas.addAll(porCancelacion.values());

            List<ReporteDevolucionesRespuesta.Sector> sectores = new ArrayList<>();
            List<ReporteDevolucionesRespuesta.Cantidades> parciales = new ArrayList<>();
            for (Map.Entry<String, String> s : sectoresDelReporte(evento, todas).entrySet()) {
                String id = s.getKey();
                ReporteDevolucionesRespuesta.Cantidades c = cantidades(
                        vendidas.getOrDefault(id, vacio(id)).cantidad(),
                        porDevolucion.getOrDefault(id, vacio(id)), porCancelacion.getOrDefault(id, vacio(id)));
                sectores.add(new ReporteDevolucionesRespuesta.Sector(id, s.getValue(), c));
                parciales.add(c);
            }
            return new ReporteDevolucionesRespuesta(encabezado(evento), sumar(parciales), sectores,
                    cantidadImporte(solicitudes.enCurso(eventoId)));
        });
    }

    /**
     * Las devoluciones de un cliente y las de una cancelación no se solapan (la cancelación excluye
     * las entradas con devolución aprobada), así que las devueltas son la suma de ambas.
     */
    private static ReporteDevolucionesRespuesta.Cantidades cantidades(long vendidas, TotalPorSector devolucion,
                                                                     TotalPorSector cancelacion) {
        long devueltas = devolucion.cantidad() + cancelacion.cantidad();
        return new ReporteDevolucionesRespuesta.Cantidades(vendidas, devueltas, devolucion.cantidad(),
                cancelacion.cantidad(), vendidas - devueltas,
                dinero(devolucion.importe().add(cancelacion.importe())),
                dinero(devolucion.importe()), dinero(cancelacion.importe()));
    }

    private static ReporteDevolucionesRespuesta.Cantidades sumar(List<ReporteDevolucionesRespuesta.Cantidades> partes) {
        long vendidas = 0, devueltas = 0, porDevolucion = 0, porCancelacion = 0, netas = 0;
        BigDecimal importe = BigDecimal.ZERO, importeDevolucion = BigDecimal.ZERO, importeCancelacion = BigDecimal.ZERO;
        for (ReporteDevolucionesRespuesta.Cantidades c : partes) {
            vendidas += c.entradasVendidas();
            devueltas += c.entradasDevueltas();
            porDevolucion += c.devueltasPorDevolucion();
            porCancelacion += c.devueltasPorCancelacion();
            netas += c.entradasNetas();
            importe = importe.add(c.importeDevuelto());
            importeDevolucion = importeDevolucion.add(c.importeDevueltoPorDevolucion());
            importeCancelacion = importeCancelacion.add(c.importeDevueltoPorCancelacion());
        }
        return new ReporteDevolucionesRespuesta.Cantidades(vendidas, devueltas, porDevolucion, porCancelacion, netas,
                dinero(importe), dinero(importeDevolucion), dinero(importeCancelacion));
    }

    // ------------------------------------------------------------------ recaudación

    public ReporteRecaudacionRespuesta recaudacion(UsuarioActual usuario, String eventoId) {
        Evento evento = eventoAutorizado(usuario, eventoId);
        return lectura.execute(tx -> {
            Map<String, TotalPorSector> bruta = porSector(compras.ventasPorSector(eventoId));

            List<ReintegroPorSector> porSectorYEstado = new ArrayList<>(reintegros.reintegrosDeDevolucionPorSector(eventoId));
            porSectorYEstado.addAll(reintegros.reintegrosDeCancelacionPorSector(eventoId));
            Map<String, BigDecimal> exitosos = new HashMap<>();
            List<TotalPorSector> nombres = new ArrayList<>(bruta.values());
            for (ReintegroPorSector r : porSectorYEstado) {
                nombres.add(new TotalPorSector(r.sectorId(), r.sectorNombre(), 0, null));
                if (r.estado() == EstadoReintegro.EXITOSO) {
                    exitosos.merge(r.sectorId(), r.importe(), BigDecimal::add);
                }
            }

            List<ReporteRecaudacionRespuesta.Sector> sectores = new ArrayList<>();
            BigDecimal totalBruta = BigDecimal.ZERO, totalReintegros = BigDecimal.ZERO;
            for (Map.Entry<String, String> s : sectoresDelReporte(evento, nombres).entrySet()) {
                BigDecimal b = bruta.getOrDefault(s.getKey(), vacio(s.getKey())).importe();
                BigDecimal r = exitosos.getOrDefault(s.getKey(), BigDecimal.ZERO);
                sectores.add(new ReporteRecaudacionRespuesta.Sector(s.getKey(), s.getValue(), montos(b, r)));
                totalBruta = totalBruta.add(b);
                totalReintegros = totalReintegros.add(r);
            }

            Map<EstadoReintegro, ReintegrosPorEstado> porEstado = new HashMap<>();
            reintegros.reintegrosDeConfirmadasPorEstado(eventoId).forEach(r -> porEstado.put(r.estado(), r));
            ReporteRecaudacionRespuesta.Reconciliacion reconciliacion = new ReporteRecaudacionRespuesta.Reconciliacion(
                    cantidadImporte(porEstado.get(EstadoReintegro.PENDIENTE)),
                    cantidadImporte(porEstado.get(EstadoReintegro.FALLIDO)));

            CantidadEImporte cobradas = compras.cobradasNoConfirmadas(eventoId);
            BigDecimal reintegrado = reintegros.reintegradoDeNoConfirmadas(eventoId);
            ReporteRecaudacionRespuesta.NoConfirmadas noConfirmadas = new ReporteRecaudacionRespuesta.NoConfirmadas(
                    cobradas.cantidad(), dinero(cobradas.importe()),
                    dinero(reintegrado == null ? BigDecimal.ZERO : reintegrado));

            return new ReporteRecaudacionRespuesta(encabezado(evento), montos(totalBruta, totalReintegros), sectores,
                    reconciliacion, noConfirmadas);
        });
    }

    private static ReporteRecaudacionRespuesta.Montos montos(BigDecimal bruta, BigDecimal reintegros) {
        return new ReporteRecaudacionRespuesta.Montos(dinero(bruta), dinero(reintegros), dinero(bruta.subtract(reintegros)));
    }

    // ------------------------------------------------------------------ ocupación

    /**
     * El inventario lo informa Disponibilidad y reservas: si no responde, 503 (sin datos parciales);
     * si no tiene inventario del evento, se informa {@code inventarioInicializado} falso.
     */
    public ReporteOcupacionRespuesta ocupacion(UsuarioActual usuario, String eventoId) {
        Evento evento = eventoAutorizado(usuario, eventoId);
        Optional<Disponibilidad> disponibilidad = reservas.obtenerDisponibilidad(eventoId);
        EncabezadoReporte encabezado = lectura.execute(tx -> encabezado(evento));
        if (disponibilidad.isEmpty()) {
            return new ReporteOcupacionRespuesta(encabezado, false, ocupacion(0, 0, 0), List.of());
        }
        Map<String, String> nombres = new HashMap<>();
        if (evento.sectores() != null) {
            evento.sectores().forEach(s -> nombres.put(s.sectorId(), s.nombre()));
        }
        List<ReporteOcupacionRespuesta.Sector> sectores = new ArrayList<>();
        int capacidad = 0, vendidos = 0, reservados = 0;
        List<SectorDisponibilidad> delInventario = disponibilidad.get().sectores();
        for (SectorDisponibilidad s : delInventario == null ? List.<SectorDisponibilidad>of() : delInventario) {
            int c = valor(s.capacidadTotal()), v = valor(s.cantidadVendida()), r = valor(s.cantidadReservada());
            sectores.add(new ReporteOcupacionRespuesta.Sector(s.sectorId(), nombres.get(s.sectorId()), ocupacion(c, v, r)));
            capacidad += c;
            vendidos += v;
            reservados += r;
        }
        return new ReporteOcupacionRespuesta(encabezado, true, ocupacion(capacidad, vendidos, reservados), sectores);
    }

    /** Vendidos / capacidad × 100 con 2 decimales; las reservas temporales no suman. Capacidad 0 → 0.00. */
    static ReporteOcupacionRespuesta.Ocupacion ocupacion(int capacidad, int vendidos, int reservados) {
        BigDecimal porcentaje = capacidad <= 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(vendidos).multiply(CIEN).divide(BigDecimal.valueOf(capacidad), 2, RoundingMode.HALF_UP);
        return new ReporteOcupacionRespuesta.Ocupacion(capacidad, vendidos, reservados, porcentaje);
    }

    private static int valor(Integer n) {
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ utilidades

    private static Map<String, TotalPorSector> porSector(List<TotalPorSector> filas) {
        Map<String, TotalPorSector> mapa = new LinkedHashMap<>();
        filas.forEach(f -> mapa.put(f.sectorId(), f));
        return mapa;
    }

    private static TotalPorSector vacio(String sectorId) {
        return new TotalPorSector(sectorId, null, 0, BigDecimal.ZERO);
    }

    /**
     * Sectores del reporte (id → nombre): los del evento según Eventos, en su orden, y después los
     * que aparecen en los datos pero Eventos ya no informa, con el nombre guardado en la compra.
     */
    private static Map<String, String> sectoresDelReporte(Evento evento, Collection<TotalPorSector> datos) {
        Map<String, String> sectores = new LinkedHashMap<>();
        if (evento.sectores() != null) {
            evento.sectores().forEach(s -> sectores.put(s.sectorId(), s.nombre()));
        }
        Map<String, String> otros = new TreeMap<>();
        for (TotalPorSector d : datos) {
            if (d.sectorId() != null && !sectores.containsKey(d.sectorId())) {
                otros.merge(d.sectorId(), d.sectorNombre() == null ? "" : d.sectorNombre(),
                        (a, b) -> a.isEmpty() ? b : a);
            }
        }
        otros.forEach((id, nombre) -> sectores.put(id, nombre.isEmpty() ? null : nombre));
        return sectores;
    }

    private static BigDecimal dinero(BigDecimal importe) {
        return (importe == null ? BigDecimal.ZERO : importe).setScale(2, RoundingMode.HALF_UP);
    }

    private static CantidadImporte cantidadImporte(CantidadEImporte c) {
        return c == null ? new CantidadImporte(0, dinero(null)) : new CantidadImporte(c.cantidad(), dinero(c.importe()));
    }

    private static CantidadImporte cantidadImporte(ReintegrosPorEstado r) {
        return r == null ? new CantidadImporte(0, dinero(null)) : new CantidadImporte(r.cantidad(), dinero(r.importe()));
    }
}
