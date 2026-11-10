package ticketya.modelo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "compras")
public class Compra {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoCompra estado = EstadoCompra.PENDIENTE;

    @Column(nullable = false, length = 64)
    private String clienteId;

    // Una sola compra por reserva: evita cobrar y vender dos veces los mismos lugares
    @Column(nullable = false, length = 64, unique = true)
    private String reservaId;

    @Column(nullable = false, length = 64)
    private String eventoId;

    @Column(nullable = false)
    private String eventoNombre;

    @Column(nullable = false)
    private Instant eventoFechaInicio;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    private String medioDePago;

    // ID del pago en el servicio de Pagos; queda vacío hasta que se aprueba el cobro.
    // Se necesita para pedir reintegros (/payments/{pagoId}/refunds).
    @Column(length = 64)
    private String pagoId;

    // Reservas ya confirmó la venta: al retomar el pago no se vuelve a confirmar
    @Column(nullable = false)
    private boolean ventaConfirmada = false;

    @Column(nullable = false)
    private Instant fecha = Instant.now();

    @OneToMany(mappedBy = "compra", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<DetalleCompra> detalle = new ArrayList<>();

    public void agregarDetalle(DetalleCompra d) {
        d.setCompra(this);
        detalle.add(d);
    }

    public UUID getId() { return id; }
    public EstadoCompra getEstado() { return estado; }
    public void setEstado(EstadoCompra estado) { this.estado = estado; }
    public String getClienteId() { return clienteId; }
    public void setClienteId(String clienteId) { this.clienteId = clienteId; }
    public String getReservaId() { return reservaId; }
    public void setReservaId(String reservaId) { this.reservaId = reservaId; }
    public String getEventoId() { return eventoId; }
    public void setEventoId(String eventoId) { this.eventoId = eventoId; }
    public String getEventoNombre() { return eventoNombre; }
    public void setEventoNombre(String eventoNombre) { this.eventoNombre = eventoNombre; }
    public Instant getEventoFechaInicio() { return eventoFechaInicio; }
    public void setEventoFechaInicio(Instant eventoFechaInicio) { this.eventoFechaInicio = eventoFechaInicio; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getMedioDePago() { return medioDePago; }
    public void setMedioDePago(String medioDePago) { this.medioDePago = medioDePago; }
    public String getPagoId() { return pagoId; }
    public void setPagoId(String pagoId) { this.pagoId = pagoId; }
    public boolean isVentaConfirmada() { return ventaConfirmada; }
    public void setVentaConfirmada(boolean ventaConfirmada) { this.ventaConfirmada = ventaConfirmada; }
    public Instant getFecha() { return fecha; }
    public void setFecha(Instant fecha) { this.fecha = fecha; }
    public List<DetalleCompra> getDetalle() { return detalle; }
}