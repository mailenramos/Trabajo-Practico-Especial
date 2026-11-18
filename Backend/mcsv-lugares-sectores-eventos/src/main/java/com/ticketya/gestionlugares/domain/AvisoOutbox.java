package com.ticketya.gestionlugares.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "outbox")
@CompoundIndex(name = "idx_outbox_estado_proximo_intento",
        def = "{'estado': 1, 'proximoIntento': 1, 'intentos': 1}")
@CompoundIndex(name = "idx_outbox_estado_lease",
        def = "{'estado': 1, 'leaseHasta': 1, 'intentos': 1}")
public class AvisoOutbox {

    @Id
    private String id;
    private String tipo;
    private String eventoId;
    private String destino;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private EstadoAvisoOutbox estado = EstadoAvisoOutbox.PENDIENTE;
    private int intentos;
    private Instant proximoIntento;
    private String ultimoError;
    private Instant creadoEn;
    private Instant actualizadoEn;
    private Instant leaseHasta;
    private Instant entregadoEn;

    public AvisoOutbox() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getEventoId() { return eventoId; }
    public void setEventoId(String eventoId) { this.eventoId = eventoId; }
    public String getDestino() { return destino; }
    public void setDestino(String destino) { this.destino = destino; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public EstadoAvisoOutbox getEstado() { return estado; }
    public void setEstado(EstadoAvisoOutbox estado) { this.estado = estado; }
    public int getIntentos() { return intentos; }
    public void setIntentos(int intentos) { this.intentos = intentos; }
    public Instant getProximoIntento() { return proximoIntento; }
    public void setProximoIntento(Instant proximoIntento) { this.proximoIntento = proximoIntento; }
    public String getUltimoError() { return ultimoError; }
    public void setUltimoError(String ultimoError) { this.ultimoError = ultimoError; }
    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
    public Instant getLeaseHasta() { return leaseHasta; }
    public void setLeaseHasta(Instant leaseHasta) { this.leaseHasta = leaseHasta; }
    public Instant getEntregadoEn() { return entregadoEn; }
    public void setEntregadoEn(Instant entregadoEn) { this.entregadoEn = entregadoEn; }
}
