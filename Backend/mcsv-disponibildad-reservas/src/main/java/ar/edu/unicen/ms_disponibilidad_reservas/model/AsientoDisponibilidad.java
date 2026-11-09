package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Entity
@Table(name = "asientos_disponibilidad", uniqueConstraints = {
        @UniqueConstraint(name = "uk_asiento_evento_sector_fila_numero", columnNames = {
                "evento_id", "sector_id", "fila", "numero"
        })
}, check = {
        @CheckConstraint(name = "ck_asiento_numero_positivo", constraint = "numero >= 1")
})
public class AsientoDisponibilidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @NotNull
    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    @NotNull
    @Column(name = "fila", nullable = false)
    private Integer fila;

    @NotNull
    @Min(1)
    @Column(name = "numero", nullable = false)
    private Integer numero;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16,
            columnDefinition = "varchar(16) not null default 'LIBRE'")
    private EstadoAsiento estado = EstadoAsiento.LIBRE;

    public AsientoDisponibilidad() {
    }

    public Long getId() {
        return id;
    }

    public String getEventoId() {
        return eventoId;
    }

    public void setEventoId(String eventoId) {
        this.eventoId = eventoId;
    }

    public String getSectorId() {
        return sectorId;
    }

    public void setSectorId(String sectorId) {
        this.sectorId = sectorId;
    }

    public Integer getFila() {
        return fila;
    }

    public void setFila(Integer fila) {
        this.fila = fila;
    }

    public Integer getNumero() {
        return numero;
    }

    public void setNumero(Integer numero) {
        this.numero = numero;
    }

    public EstadoAsiento getEstado() {
        return estado;
    }

    public void setEstado(EstadoAsiento estado) {
        this.estado = estado;
    }
}
