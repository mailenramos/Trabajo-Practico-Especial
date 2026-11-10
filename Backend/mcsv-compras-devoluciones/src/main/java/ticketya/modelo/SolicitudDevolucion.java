package ticketya.modelo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Solicitud de devolución de una entrada. Su id es el devolucionId, que también es el
 * refundRequestId y la Idempotency-Key del reintegro en Pagos.
 */
@Entity
@Table(name = "solicitudes_devolucion")
public class SolicitudDevolucion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String clienteId;

    @Column(nullable = false, length = 64)
    private String entradaId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID compraId;

    // Lugar y precio copiados del detalle de la compra
    @Column(nullable = false, length = 64)
    private String sectorId;

    @Column(nullable = false)
    private String sectorNombre;

    private Integer fila;
    private Integer numero;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 64)
    private String eventoId;

    @Column(nullable = false)
    private String eventoNombre;

    @Column(nullable = false)
    private Instant eventoFechaInicio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoSolicitud estado = EstadoSolicitud.PENDIENTE;

    private String motivoRechazo;

    @Column(nullable = false)
    private Instant creadaEn = Instant.now();

    private Instant resueltaEn;

    @Column(length = 64)
    private String resueltaPor;

    // Copia del entradaId mientras la solicitud está pendiente o aprobada; NULL al rechazarla.
    // La restricción única impide dos solicitudes activas de la misma entrada.
    @Column(unique = true, length = 64)
    private String entradaActiva;

    // Pasos de la coordinación de una devolución aprobada
    @Column(nullable = false)
    private boolean entradaEnReintegro = false;

    @Column(nullable = false)
    private boolean lugarLiberado = false;

    @Column(nullable = false)
    private boolean entradaDevuelta = false;

    // Último paso que falló, para retomar la coordinación
    @Column(length = 500)
    private String ultimoError;

    protected SolicitudDevolucion() { }

    public SolicitudDevolucion(String clienteId, Compra compra, DetalleCompra detalle) {
        this.clienteId = clienteId;
        this.entradaId = detalle.getEntradaId();
        this.entradaActiva = detalle.getEntradaId();
        this.compraId = compra.getId();
        this.sectorId = detalle.getSectorId();
        this.sectorNombre = detalle.getSectorNombre();
        this.fila = detalle.getFila();
        this.numero = detalle.getNumero();
        this.monto = detalle.getPrecioUnitario();
        this.eventoId = compra.getEventoId();
        this.eventoNombre = compra.getEventoNombre();
        this.eventoFechaInicio = compra.getEventoFechaInicio();
    }

    /** Todos los pasos de la coordinación están hechos. */
    public boolean coordinacionCompleta() {
        return entradaEnReintegro && lugarLiberado && entradaDevuelta;
    }

    public UUID getId() { return id; }
    public String getClienteId() { return clienteId; }
    public String getEntradaId() { return entradaId; }
    public UUID getCompraId() { return compraId; }
    public String getSectorId() { return sectorId; }
    public String getSectorNombre() { return sectorNombre; }
    public Integer getFila() { return fila; }
    public Integer getNumero() { return numero; }
    public BigDecimal getMonto() { return monto; }
    public String getEventoId() { return eventoId; }
    public String getEventoNombre() { return eventoNombre; }
    public Instant getEventoFechaInicio() { return eventoFechaInicio; }
    public EstadoSolicitud getEstado() { return estado; }
    public String getMotivoRechazo() { return motivoRechazo; }
    public Instant getCreadaEn() { return creadaEn; }
    public Instant getResueltaEn() { return resueltaEn; }
    public String getResueltaPor() { return resueltaPor; }
    public String getEntradaActiva() { return entradaActiva; }
    public boolean isEntradaEnReintegro() { return entradaEnReintegro; }
    public void setEntradaEnReintegro(boolean v) { this.entradaEnReintegro = v; }
    public boolean isLugarLiberado() { return lugarLiberado; }
    public void setLugarLiberado(boolean v) { this.lugarLiberado = v; }
    public boolean isEntradaDevuelta() { return entradaDevuelta; }
    public void setEntradaDevuelta(boolean v) { this.entradaDevuelta = v; }
    public String getUltimoError() { return ultimoError; }
    public void setUltimoError(String ultimoError) {
        this.ultimoError = ultimoError == null || ultimoError.length() <= 500
                ? ultimoError : ultimoError.substring(0, 500);
    }
}
