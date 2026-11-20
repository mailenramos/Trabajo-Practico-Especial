package ar.edu.unicen.ms_fila_virtual.model;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoFila;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "filas_evento", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fila_evento_evento", columnNames = "evento_id")
})
public class FilaEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evento_id", nullable = false, length = 255)
    private String eventoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    private EstadoFila estado = EstadoFila.DESACTIVADA;

    @Column(name = "cupo", nullable = false)
    private Integer cupo = 10;

    @Column(name = "duracion_minutos", nullable = false)
    private Integer duracionMinutos = 5;

    @Column(name = "cancelado", nullable = false)
    private boolean cancelado = false;

    public FilaEvento() {
    }

    public Long getId() { return id; }

    public String getEventoId() { return eventoId; }

    public void setEventoId(String eventoId) { this.eventoId = eventoId; }

    public EstadoFila getEstado() { return estado; }

    public void setEstado(EstadoFila estado) { this.estado = estado; }

    public Integer getCupo() { return cupo; }

    public void setCupo(Integer cupo) { this.cupo = cupo; }

    public Integer getDuracionMinutos() { return duracionMinutos; }

    public void setDuracionMinutos(Integer duracionMinutos) { this.duracionMinutos = duracionMinutos; }

    public boolean isCancelado() { return cancelado; }

    public void setCancelado(boolean cancelado) { this.cancelado = cancelado; }
}
