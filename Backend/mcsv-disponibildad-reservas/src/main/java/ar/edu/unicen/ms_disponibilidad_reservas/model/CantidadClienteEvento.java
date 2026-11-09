package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "cantidades_cliente_evento", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cantidad_cliente_evento_usuario_evento", columnNames = {
                "usuario_id", "evento_id"
        })
}, check = {
        @CheckConstraint(name = "ck_cantidad_cliente_evento_no_negativa",
                constraint = "cantidad_reservada >= 0 AND cantidad_confirmada >= 0")
})
public class CantidadClienteEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private String usuarioId;

    @NotNull
    @Column(name = "evento_id", nullable = false, updatable = false)
    private String eventoId;

    @NotNull
    @Min(0)
    @Column(name = "cantidad_reservada", nullable = false)
    private Integer cantidadReservada = 0;

    @NotNull
    @Min(0)
    @Column(name = "cantidad_confirmada", nullable = false)
    private Integer cantidadConfirmada = 0;

    public CantidadClienteEvento() {
    }

    public CantidadClienteEvento(String usuarioId, String eventoId) {
        this.usuarioId = usuarioId;
        this.eventoId = eventoId;
    }

    public Long getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(String usuarioId) {
        this.usuarioId = usuarioId;
    }

    public String getEventoId() {
        return eventoId;
    }

    public void setEventoId(String eventoId) {
        this.eventoId = eventoId;
    }

    public Integer getCantidadReservada() {
        return cantidadReservada;
    }

    public void setCantidadReservada(Integer cantidadReservada) {
        this.cantidadReservada = cantidadReservada;
    }

    public Integer getCantidadConfirmada() {
        return cantidadConfirmada;
    }

    public void setCantidadConfirmada(Integer cantidadConfirmada) {
        this.cantidadConfirmada = cantidadConfirmada;
    }
}
