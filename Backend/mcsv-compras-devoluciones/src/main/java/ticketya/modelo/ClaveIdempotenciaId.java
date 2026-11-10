package ticketya.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Clave primaria compuesta: la clave de idempotencia solo tiene efecto para el
 * cliente que la envió. Dos clientes pueden usar el mismo valor sin pisarse.
 */
@Embeddable
public class ClaveIdempotenciaId implements Serializable {

    @Column(name = "cliente_id", nullable = false, length = 64)
    private String clienteId;

    @Column(name = "clave", nullable = false, length = 100)
    private String clave;

    protected ClaveIdempotenciaId() { }

    public ClaveIdempotenciaId(String clienteId, String clave) {
        this.clienteId = clienteId;
        this.clave = clave;
    }

    public String getClienteId() { return clienteId; }
    public String getClave() { return clave; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClaveIdempotenciaId otro)) return false;
        return Objects.equals(clienteId, otro.clienteId) && Objects.equals(clave, otro.clave);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clienteId, clave);
    }
}
