package ar.edu.unicen.ms_disponibilidad_reservas.model;

import java.time.LocalDateTime;
import java.util.Objects;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoEvento;

import jakarta.persistence.*;

@Entity
@Table(name = "eventos_disponibilidad")
@Access(AccessType.FIELD)
public class EventoDisponibilidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventoId;

    /** Flyway owns the allowed-value CHECK; expose the Java enum through the accessors. */
    @Column(name = "estado_evento", nullable = false, length = 32, columnDefinition = "VARCHAR(32)")
    private String estadoEvento = EstadoEvento.PENDIENTE_PUBLICACION.name();

    @Column(nullable = false)
    private boolean ventaSuspendida = false;

    private Integer limiteEntradasPorCliente;

    /** Huella del contenido original de inicialización, independiente de cambios posteriores. */
    @Column(name = "huella_inicializacion", length = 67)
    private String huellaInicializacion;

    @Column(nullable = false)
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    // Constructor vacío
    public EventoDisponibilidad() {
    }

    // Getters y setters

    public Long getId() {
        return id;
    }

    public String getEventoId() {
        return eventoId;
    }

    public void setEventoId(String eventoId) {
        this.eventoId = eventoId;
    }

    public EstadoEvento getEstadoEvento() {
        return estadoEvento == null ? null : EstadoEvento.valueOf(estadoEvento);
    }

    public void setEstadoEvento(EstadoEvento estadoEvento) {
        this.estadoEvento = Objects.requireNonNull(estadoEvento, "estadoEvento no puede ser null").name();
    }

    public boolean isVentaSuspendida() {
        return ventaSuspendida;
    }

    public void setVentaSuspendida(boolean ventaSuspendida) {
        this.ventaSuspendida = ventaSuspendida;
    }

    public Integer getLimiteEntradasPorCliente() {
        return limiteEntradasPorCliente;
    }

    public void setLimiteEntradasPorCliente(Integer limiteEntradasPorCliente) {
        Objects.requireNonNull(limiteEntradasPorCliente, "limiteEntradasPorCliente no puede ser null");
        if (limiteEntradasPorCliente <= 0) {
            throw new IllegalArgumentException("El límite por cliente y evento debe ser positivo");
        }
        if (this.limiteEntradasPorCliente != null
                && !this.limiteEntradasPorCliente.equals(limiteEntradasPorCliente)) {
            throw new IllegalStateException("El límite por cliente y evento no se puede modificar");
        }
        this.limiteEntradasPorCliente = limiteEntradasPorCliente;
    }

    public String getHuellaInicializacion() {
        return huellaInicializacion;
    }

    public void setHuellaInicializacion(String huellaInicializacion) {
        this.huellaInicializacion = huellaInicializacion;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }
}
