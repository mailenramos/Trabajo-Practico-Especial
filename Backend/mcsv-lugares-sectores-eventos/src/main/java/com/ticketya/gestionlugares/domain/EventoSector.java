package com.ticketya.gestionlugares.domain;

import java.math.BigDecimal;

public class EventoSector {

    private String sectorId;
    private String nombre;
    private TipoSector tipo;
    private Integer filas;
    private Integer asientosPorFila;
    private Integer capacidad;
    private BigDecimal precio;

    public EventoSector() {
    }

    public EventoSector(String sectorId, String nombre, TipoSector tipo, Integer filas,
            Integer asientosPorFila, Integer capacidad, BigDecimal precio) {
        this.sectorId = sectorId;
        this.nombre = nombre;
        this.tipo = tipo;
        this.filas = filas;
        this.asientosPorFila = asientosPorFila;
        this.capacidad = capacidad;
        this.precio = precio;
    }

    public String getSectorId() { return sectorId; }
    public void setSectorId(String sectorId) { this.sectorId = sectorId; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public TipoSector getTipo() { return tipo; }
    public void setTipo(TipoSector tipo) { this.tipo = tipo; }
    public Integer getFilas() { return filas; }
    public void setFilas(Integer filas) { this.filas = filas; }
    public Integer getAsientosPorFila() { return asientosPorFila; }
    public void setAsientosPorFila(Integer asientosPorFila) { this.asientosPorFila = asientosPorFila; }
    public Integer getCapacidad() { return capacidad; }
    public void setCapacidad(Integer capacidad) { this.capacidad = capacidad; }
    public BigDecimal getPrecio() { return precio; }
    public void setPrecio(BigDecimal precio) { this.precio = precio; }
}
