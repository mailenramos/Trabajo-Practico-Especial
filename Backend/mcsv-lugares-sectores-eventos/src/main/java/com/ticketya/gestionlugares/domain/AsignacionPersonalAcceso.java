package com.ticketya.gestionlugares.domain;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "personal_acceso")
@CompoundIndex(name = "idx_personal_acceso_evento_usuario",
        def = "{'eventoId': 1, 'usuarioId': 1}", unique = true)
public class AsignacionPersonalAcceso {

    @Id
    private String id;
    private String eventoId;
    private String usuarioId;
    private EstadoPersonalAcceso estado;
    private Instant asignadaEn;
    private Instant revocadaEn;

    public AsignacionPersonalAcceso() {
    }

    public AsignacionPersonalAcceso(String eventoId, String usuarioId, Instant asignadaEn) {
        this.eventoId = Objects.requireNonNull(eventoId, "eventoId no puede ser null");
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId no puede ser null");
        this.asignadaEn = Objects.requireNonNull(asignadaEn, "asignadaEn no puede ser null");
        this.estado = EstadoPersonalAcceso.ACTIVA;
    }

    public String getId() { return id; }
    public String getEventoId() { return eventoId; }
    public String getUsuarioId() { return usuarioId; }
    public EstadoPersonalAcceso getEstado() { return estado; }
    public Instant getAsignadaEn() { return asignadaEn; }
    public Instant getRevocadaEn() { return revocadaEn; }
}
