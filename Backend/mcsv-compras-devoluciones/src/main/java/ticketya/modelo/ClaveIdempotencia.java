package ticketya.modelo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "claves_idempotencia")
public class ClaveIdempotencia implements Persistable<ClaveIdempotenciaId> {

    // La clave primaria es (clienteId, clave): la base impide repetirla para el mismo cliente
    @EmbeddedId
    private ClaveIdempotenciaId id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID compraId;

    // Resumen (hash) de los datos del pedido, para detectar "misma clave, datos distintos"
    @Column(nullable = false, length = 64)
    private String huellaPedido;

    // Clave que este servicio envía a Pagos como Idempotency-Key del cobro.
    // Se genera una sola vez, para que retomar el pago nunca cobre dos veces.
    @Column(nullable = false, length = 36)
    private String claveCobro = UUID.randomUUID().toString();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoClave estado = EstadoClave.EN_PROCESO;

    // Resultado original que se devuelve si el pedido se repite
    @Column(columnDefinition = "TEXT")
    private String resultado;

    @Column(nullable = false)
    private Instant creadaEn = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // Cuándo un pedido tomó la clave para procesarla. Si el servicio se cae a mitad de
    // camino la clave queda EN_PROCESO; pasado un plazo, otro pedido puede retomarla.
    @Column(nullable = false)
    private Instant tomadaEn = creadaEn;

    // Permite que save() haga INSERT (y falle si la clave ya existe) en vez de un merge
    @Transient
    private boolean nueva = true;

    protected ClaveIdempotencia() { }

    public ClaveIdempotencia(String clienteId, String clave, UUID compraId, String huellaPedido) {
        this.id = new ClaveIdempotenciaId(clienteId, clave);
        this.compraId = compraId;
        this.huellaPedido = huellaPedido;
    }

    @PostLoad
    @PostPersist
    void marcarComoExistente() {
        this.nueva = false;
    }

    @Override
    public ClaveIdempotenciaId getId() { return id; }

    @Override
    public boolean isNew() { return nueva; }

    public String getClienteId() { return id.getClienteId(); }
    public String getClave() { return id.getClave(); }
    public UUID getCompraId() { return compraId; }
    public String getHuellaPedido() { return huellaPedido; }
    public String getClaveCobro() { return claveCobro; }
    public EstadoClave getEstado() { return estado; }
    public void setEstado(EstadoClave estado) { this.estado = estado; }
    public String getResultado() { return resultado; }
    public void setResultado(String resultado) { this.resultado = resultado; }
    public Instant getCreadaEn() { return creadaEn; }
    public Instant getTomadaEn() { return tomadaEn; }
}
