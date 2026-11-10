package ticketya.modelo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Entrada cubierta por un reintegro por cancelación de un evento. Sirve para pasarla a
 * devuelta cuando el reintegro sale bien y para no incluirla en otro reintegro.
 */
@Entity
@Table(name = "entradas_de_reintegro",
        uniqueConstraints = @UniqueConstraint(columnNames = {"reintegroId", "entradaId"}))
public class EntradaDeReintegro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private UUID reintegroId;

    @Column(nullable = false, length = 64)
    private String entradaId;

    // La entrada ya pasó a devuelta en el servicio de entradas
    @Column(nullable = false)
    private boolean devuelta = false;

    protected EntradaDeReintegro() { }

    public EntradaDeReintegro(UUID reintegroId, String entradaId) {
        this.reintegroId = reintegroId;
        this.entradaId = entradaId;
    }

    public Long getId() { return id; }
    public UUID getReintegroId() { return reintegroId; }
    public String getEntradaId() { return entradaId; }
    public boolean isDevuelta() { return devuelta; }
    public void setDevuelta(boolean devuelta) { this.devuelta = devuelta; }
}
