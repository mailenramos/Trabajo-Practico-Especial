package ticketya.modelo;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Aviso de modificación de un evento (fecha, horario o lugar) recibido de Eventos. La restricción
 * única (evento, versión) hace idempotente el aviso: la misma versión se procesa una sola vez.
 */
@Entity
@Table(name = "modificaciones_evento",
        uniqueConstraints = @UniqueConstraint(name = "uk_modificacion_evento_version",
                columnNames = {"evento_id", "version"}))
public class ModificacionEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evento_id", nullable = false, length = 64)
    private String eventoId;

    // Contador de modificaciones del evento que lleva Eventos (>= 1)
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "recibida_en", nullable = false)
    private Instant recibidaEn = Instant.now();

    protected ModificacionEvento() { }

    public ModificacionEvento(String eventoId, int version) {
        this.eventoId = eventoId;
        this.version = version;
    }

    public Long getId() { return id; }
    public String getEventoId() { return eventoId; }
    public int getVersion() { return version; }
    public Instant getRecibidaEn() { return recibidaEn; }
}
