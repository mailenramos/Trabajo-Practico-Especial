package com.ticketya.gestionlugares.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(name = "CreateEventoRequest", description = "Datos para crear un evento en borrador.")
public class CreateEventoRequest {

    @NotBlank
    private String nombre;
    @NotBlank
    private String descripcion;
    @NotBlank
    private String tipo;
    @NotEmpty
    private List<@NotBlank String> participantes;
    @NotNull @Future
    private Instant fechaHora;
    @NotBlank
    private String lugarId;
    @NotNull @Min(1)
    private Integer limiteEntradasPorCliente = 4;

    public CreateEventoRequest() {
    }

    public CreateEventoRequest(String nombre, String descripcion, String tipo, List<String> participantes,
            Instant fechaHora, String lugarId, Integer limiteEntradasPorCliente) {
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.tipo = tipo;
        this.participantes = participantes;
        this.fechaHora = fechaHora;
        this.lugarId = lugarId;
        this.limiteEntradasPorCliente = limiteEntradasPorCliente;
    }

    public String nombre() { return nombre; }
    public String descripcion() { return descripcion; }
    public String tipo() { return tipo; }
    public List<String> participantes() { return participantes; }
    public Instant fechaHora() { return fechaHora; }
    public String lugarId() { return lugarId; }
    public Integer limiteEntradasPorCliente() { return limiteEntradasPorCliente; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public List<String> getParticipantes() { return participantes; }
    public void setParticipantes(List<String> participantes) { this.participantes = participantes; }
    public Instant getFechaHora() { return fechaHora; }
    public void setFechaHora(Instant fechaHora) { this.fechaHora = fechaHora; }
    public String getLugarId() { return lugarId; }
    public void setLugarId(String lugarId) { this.lugarId = lugarId; }

    @Schema(description = "Límite entero de entradas por cliente; si se omite se aplica 4.", minimum = "1", defaultValue = "4")
    public Integer getLimiteEntradasPorCliente() { return limiteEntradasPorCliente; }

    @JsonSetter(value = "limiteEntradasPorCliente", nulls = Nulls.SKIP)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    public void setLimiteEntradasPorCliente(Integer limiteEntradasPorCliente) {
        this.limiteEntradasPorCliente = limiteEntradasPorCliente;
    }
}
