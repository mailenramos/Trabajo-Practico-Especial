package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Links a returned numbered seat to its original reservation detail. */
@Entity
@Table(name = "devolucion_detalles_asientos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_devolucion_asiento_detalle_original", columnNames = "detalle_reserva_id")
})
public class DevolucionDetalleAsiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "devolucion_id", nullable = false, length = 36)
    private String devolucionId;

    @Column(name = "reserva_id", nullable = false, length = 36)
    private String reservaId;

    @Column(name = "detalle_reserva_id", nullable = false)
    private Long detalleReservaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "devolucion_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_devolucion_asiento_devolucion"))
    private Devolucion devolucion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_reserva_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_devolucion_asiento_detalle"))
    private DetalleReserva detalleReserva;

    protected DevolucionDetalleAsiento() {
    }
}
