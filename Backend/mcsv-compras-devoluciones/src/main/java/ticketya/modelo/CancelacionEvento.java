package ticketya.modelo;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Aviso de cancelación de un evento (E-1). La clave primaria es el eventoId: la base
 * impide registrar dos veces el mismo evento, y eso hace idempotente el aviso.
 */
@Entity
@Table(name = "cancelaciones_evento")
public class CancelacionEvento implements Persistable<String> {

    @Id
    @Column(length = 64)
    private String eventoId;

    private String motivo;

    private Instant canceladoEn;

    @Column(nullable = false)
    private Instant recibidaEn = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoCancelacion estado = EstadoCancelacion.EN_PROCESO;

    // Cuándo se tomó para procesarla. Si el servicio se cae a mitad de camino queda
    // EN_PROCESO; pasado un plazo, la reanudación automática (o un aviso repetido) la retoma.
    @Column(nullable = false)
    private Instant tomadaEn = recibidaEn;

    // Total de entradas que Entradas informó como marcadas. Solo informativo: el monto de
    // cada reintegro se calcula con los datos propios.
    private Integer entradasMarcadas;

    // La marcación de Entradas ya se hizo con éxito: al retomar no se vuelve a pedir
    @Column(nullable = false)
    private boolean marcacionHecha;

    @Column(length = 500)
    private String ultimoError;

    // Permite que save() haga INSERT (y falle si el evento ya existe) en vez de un merge
    @Transient
    private boolean nueva = true;

    protected CancelacionEvento() { }

    public CancelacionEvento(String eventoId, String motivo, Instant canceladoEn) {
        this.eventoId = eventoId;
        this.motivo = motivo;
        this.canceladoEn = canceladoEn;
    }

    @PostLoad
    @PostPersist
    void marcarComoExistente() {
        this.nueva = false;
    }

    @Override
    public String getId() { return eventoId; }

    @Override
    public boolean isNew() { return nueva; }

    public String getEventoId() { return eventoId; }
    public String getMotivo() { return motivo; }
    public Instant getCanceladoEn() { return canceladoEn; }
    public Instant getRecibidaEn() { return recibidaEn; }
    public EstadoCancelacion getEstado() { return estado; }
    public Instant getTomadaEn() { return tomadaEn; }
    public Integer getEntradasMarcadas() { return entradasMarcadas; }
    public String getUltimoError() { return ultimoError; }
    public boolean isMarcacionHecha() { return marcacionHecha; }

    /** Recorta un error al largo de la columna. */
    public static String recortar(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }
}
