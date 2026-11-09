package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "detalles_reserva", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uk_detalle_reserva_reserva_id", columnNames = { "reserva_id", "id" })
}, check = {
        @CheckConstraint(name = "ck_detalle_reserva_forma", constraint = "(fila IS NOT NULL AND numero IS NOT NULL AND numero >= 1 AND cantidad = 1) OR (fila IS NULL AND numero IS NULL AND cantidad > 0)")
})
public class DetalleReserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reserva_id", nullable = false)
    private Reserva reserva;

    @NotNull
    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    @Column(name = "fila")
    private Integer fila;

    @Min(1)
    @Column(name = "numero")
    private Integer numero;

    @NotNull
    @Positive
    @Column(name = "cantidad", nullable = false)
    private Integer cantidad;

    public DetalleReserva() {
    }

    public Long getId() {
        return id;
    }

    public Reserva getReserva() {
        return reserva;
    }

    public void setReserva(Reserva reserva) {
        this.reserva = reserva;
    }

    public String getSectorId() {
        return sectorId;
    }

    public void setSectorId(String sectorId) {
        this.sectorId = sectorId;
    }

    public Integer getFila() {
        return fila;
    }

    public void setFila(Integer fila) {
        this.fila = fila;
    }

    public Integer getNumero() {
        return numero;
    }

    public void setNumero(Integer numero) {
        this.numero = numero;
    }

    public Integer getCantidad() {
        return cantidad;
    }

    public void setCantidad(Integer cantidad) {
        this.cantidad = cantidad;
    }
}
