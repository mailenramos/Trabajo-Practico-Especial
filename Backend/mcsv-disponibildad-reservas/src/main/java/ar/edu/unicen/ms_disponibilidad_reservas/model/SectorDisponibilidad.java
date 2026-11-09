
package ar.edu.unicen.ms_disponibilidad_reservas.model;

import jakarta.persistence.*;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.TipoSector;

@Entity
@Table(name = "sectores_disponibilidad", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "evento_id", "sector_id" })
}, check = {
        @CheckConstraint(name = "ck_sector_capacidad_disponible",
                constraint = "capacidad_total >= 0 AND cantidad_reservada >= 0 AND cantidad_vendida >= 0 AND cantidad_reservada + cantidad_vendida <= capacidad_total")
})
public class SectorDisponibilidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoSector tipo;

    @Column(nullable = false)
    private Integer capacidadTotal;

    @Column(nullable = false)
    private Integer cantidadReservada = 0;

    @Column(nullable = false)
    private Integer cantidadVendida = 0;

    public SectorDisponibilidad() {
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

    public TipoSector getTipo() {
        return tipo;
    }

    public void setTipo(TipoSector tipo) {
        this.tipo = tipo;
    }

    public Integer getCapacidadTotal() {
        return capacidadTotal;
    }

    public void setCapacidadTotal(Integer capacidadTotal) {
        this.capacidadTotal = capacidadTotal;
    }

    public Integer getCantidadReservada() {
        return cantidadReservada;
    }

    public void setCantidadReservada(Integer cantidadReservada) {
        this.cantidadReservada = cantidadReservada;
    }

    public Integer getCantidadVendida() {
        return cantidadVendida;
    }

    public void setCantidadVendida(Integer cantidadVendida) {
        this.cantidadVendida = cantidadVendida;
    }
}
