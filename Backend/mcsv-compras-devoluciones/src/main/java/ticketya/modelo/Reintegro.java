package ticketya.modelo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pedido de reintegro a Pagos. La Idempotency-Key que se envía a Pagos es el mismo
 * valor que refundRequestId. Un reintegro fallido se reintenta con el endpoint
 * /retry de Pagos sobre pagosReintegroId, nunca con un refundRequestId nuevo.
 */
@Entity
@Table(name = "reintegros")
public class Reintegro {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID compraId;

    // Identifica la solicitud ante Pagos; es única
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, unique = true, length = 36)
    private UUID refundRequestId;

    // ID que devuelve Pagos; queda vacío hasta recibir la respuesta
    @Column(length = 64)
    private String pagosReintegroId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    // Se envía a Pagos como reason, que es obligatorio (400 si falta o va vacío)
    @Column(nullable = false)
    private String motivo;

    // Último error al pedir o reintentar en Pagos, para resolverlo a mano
    @Column(length = 500)
    private String ultimoError;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrigenReintegro origen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoReintegro estado = EstadoReintegro.PENDIENTE;

    @Column(nullable = false)
    private int intentos = 0;

    @Column(nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(nullable = false)
    private Instant actualizadoEn = Instant.now();

    protected Reintegro() { }

    public Reintegro(UUID compraId, UUID refundRequestId, BigDecimal monto,
                     String motivo, OrigenReintegro origen) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("El motivo del reintegro es obligatorio");
        }
        this.compraId = compraId;
        this.refundRequestId = refundRequestId;
        this.monto = monto;
        this.motivo = motivo;
        this.origen = origen;
    }

    @PreUpdate
    void alActualizar() {
        this.actualizadoEn = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCompraId() { return compraId; }
    public UUID getRefundRequestId() { return refundRequestId; }
    public String getPagosReintegroId() { return pagosReintegroId; }
    public void setPagosReintegroId(String pagosReintegroId) { this.pagosReintegroId = pagosReintegroId; }
    public BigDecimal getMonto() { return monto; }
    public String getMotivo() { return motivo; }
    public String getUltimoError() { return ultimoError; }
    public void setUltimoError(String ultimoError) {
        this.ultimoError = ultimoError == null || ultimoError.length() <= 500
                ? ultimoError : ultimoError.substring(0, 500);
    }
    public OrigenReintegro getOrigen() { return origen; }
    public EstadoReintegro getEstado() { return estado; }
    public void setEstado(EstadoReintegro estado) { this.estado = estado; }
    public int getIntentos() { return intentos; }
    public void setIntentos(int intentos) { this.intentos = intentos; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
}
