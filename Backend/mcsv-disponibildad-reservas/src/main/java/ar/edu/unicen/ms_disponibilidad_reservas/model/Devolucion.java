package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Durable global idempotency record for one approved refund request. */
@Entity
@Table(name = "devoluciones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_devolucion_id_reserva", columnNames = { "id", "reserva_id" })
})
public class Devolucion {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reserva_id", nullable = false)
    private Reserva reserva;

    @Column(name = "huella_solicitud", nullable = false, length = 64)
    private String huellaSolicitud;

    protected Devolucion() {
    }
}
