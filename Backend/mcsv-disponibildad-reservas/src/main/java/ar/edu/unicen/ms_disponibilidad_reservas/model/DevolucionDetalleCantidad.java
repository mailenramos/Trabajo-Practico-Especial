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
import jakarta.validation.constraints.Positive;

/** Records a quantity returned from one original non-numbered reservation detail. */
@Entity
@Table(name = "devolucion_detalles_cantidades", uniqueConstraints = {
        @UniqueConstraint(name = "uk_devolucion_cantidad_detalle", columnNames = { "devolucion_id", "detalle_reserva_id" })
})
public class DevolucionDetalleCantidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "devolucion_id", nullable = false, length = 36)
    private String devolucionId;

    @Column(name = "reserva_id", nullable = false, length = 36)
    private String reservaId;

    @Column(name = "detalle_reserva_id", nullable = false)
    private Long detalleReservaId;

    @Positive
    @Column(name = "cantidad", nullable = false)
    private Integer cantidad;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "devolucion_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_devolucion_cantidad_devolucion"))
    private Devolucion devolucion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_reserva_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_devolucion_cantidad_detalle"))
    private DetalleReserva detalleReserva;

    protected DevolucionDetalleCantidad() {
    }
}
