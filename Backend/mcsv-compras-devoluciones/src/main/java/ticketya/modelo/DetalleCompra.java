package ticketya.modelo;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "detalles_compra")
public class DetalleCompra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "compra_id")
    private Compra compra;

    @Column(nullable = false, length = 64)
    private String sectorId;

    @Column(nullable = false)
    private String sectorNombre;

    // Solo en sectores numerados
    private Integer fila;
    private Integer numero;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal precioUnitario;

    // Lo devuelve Entradas al emitir; queda vacío hasta entonces
    @Column(length = 64, unique = true)
    private String entradaId;

    public Long getId() { return id; }
    public Compra getCompra() { return compra; }
    public void setCompra(Compra compra) { this.compra = compra; }
    public String getSectorId() { return sectorId; }
    public void setSectorId(String sectorId) { this.sectorId = sectorId; }
    public String getSectorNombre() { return sectorNombre; }
    public void setSectorNombre(String sectorNombre) { this.sectorNombre = sectorNombre; }
    public Integer getFila() { return fila; }
    public void setFila(Integer fila) { this.fila = fila; }
    public Integer getNumero() { return numero; }
    public void setNumero(Integer numero) { this.numero = numero; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }
    public String getEntradaId() { return entradaId; }
    public void setEntradaId(String entradaId) { this.entradaId = entradaId; }
}