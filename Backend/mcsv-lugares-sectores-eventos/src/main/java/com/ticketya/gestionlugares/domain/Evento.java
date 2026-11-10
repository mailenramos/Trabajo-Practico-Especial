package com.ticketya.gestionlugares.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "eventos")
@CompoundIndex(name = "idx_eventos_estado_fecha", def = "{'estado': 1, 'fechaHora': 1}")
@CompoundIndex(name = "idx_eventos_estado_ciudad_fecha", def = "{'estado': 1, 'ciudad': 1, 'fechaHora': 1}")
@CompoundIndex(name = "idx_eventos_organizador_estado", def = "{'organizadorId': 1, 'estado': 1}")
public class Evento {

    @Id
    private String id;
    @Indexed
    private String organizadorId;
    private String lugarId;
    private String ciudad;
    @TextIndexed
    private String nombre;
    @TextIndexed
    private String descripcion;
    @Indexed(name = "idx_eventos_tipo")
    private String tipo;
    @TextIndexed
    @Indexed(name = "idx_eventos_participantes")
    private List<String> participantes = new ArrayList<>();
    private Instant fechaHora;
    private EstadoEvento estado;
    private boolean ventaSuspendida;
    private int limiteEntradasPorCliente;
    private List<EventoSector> sectores = new ArrayList<>();
    private BigDecimal precioMinimo;
    private String motivoCancelacion;
    private Instant canceladoEn;
    @Indexed
    private boolean avisoCancelacionPendiente;
    @Version
    private Long version;

    public Evento() {
    }

    public static Evento borrador(String organizadorId, String lugarId, String ciudad,
            String nombre, String descripcion, String tipo, List<String> participantes,
            Instant fechaHora, int limiteEntradasPorCliente, List<EventoSector> sectores) {
        Evento evento = new Evento();
        evento.id = new ObjectId().toHexString();
        evento.organizadorId = organizadorId;
        evento.lugarId = lugarId;
        evento.ciudad = ciudad;
        evento.nombre = nombre;
        evento.descripcion = descripcion;
        evento.tipo = tipo;
        evento.participantes = new ArrayList<>(participantes);
        evento.fechaHora = fechaHora;
        evento.estado = EstadoEvento.BORRADOR;
        evento.ventaSuspendida = false;
        evento.limiteEntradasPorCliente = limiteEntradasPorCliente;
        evento.sectores = new ArrayList<>(sectores);
        return evento;
    }

    public EventoSector buscarSector(String sectorId) {
        return sectores.stream().filter(sector -> Objects.equals(sector.getSectorId(), sectorId))
                .findFirst().orElse(null);
    }

    public void fijarPrecio(String sectorId, BigDecimal precio) {
        EventoSector sector = buscarSector(sectorId);
        if (sector == null) throw new IllegalArgumentException("El sector no pertenece al evento");
        sector.setPrecio(precio);
    }

    public void definirPrecioMinimoYPublicar() {
        if (estado != EstadoEvento.BORRADOR) throw new IllegalStateException("Solo se publica un evento en borrador");
        if (sectores.isEmpty() || sectores.stream().anyMatch(sector -> sector.getPrecio() == null)) {
            throw new IllegalArgumentException("Todos los sectores deben tener precio para publicar");
        }
        precioMinimo = sectores.stream().map(EventoSector::getPrecio).min(BigDecimal::compareTo).orElseThrow();
        estado = EstadoEvento.PUBLICADO;
    }

    public void actualizarLimiteEntradasPorCliente(int limite) {
        if (estado != EstadoEvento.BORRADOR) throw new IllegalStateException("No se puede modificar el límite luego de publicar");
        if (limite < 1) throw new IllegalArgumentException("El límite debe ser mayor o igual a 1");
        limiteEntradasPorCliente = limite;
    }

    public void suspenderVenta() {
        validarEstadoVentaEditable();
        ventaSuspendida = true;
    }

    public void reactivarVenta() {
        validarEstadoVentaEditable();
        ventaSuspendida = false;
    }

    public void cancelar() {
        if (estado != EstadoEvento.PUBLICADO && estado != EstadoEvento.AGOTADO) {
            throw new IllegalStateException("Solo se cancela un evento publicado o agotado");
        }
        estado = EstadoEvento.CANCELADO;
    }

    public void cancelar(String motivo, Instant canceladoEn) {
        cancelar();
        this.motivoCancelacion = motivo;
        this.canceladoEn = canceladoEn;
        this.avisoCancelacionPendiente = true;
    }

    public void marcarAvisoCancelacionEntregado() {
        this.avisoCancelacionPendiente = false;
    }

    public void finalizar() {
        if (estado == EstadoEvento.CANCELADO || estado == EstadoEvento.FINALIZADO) return;
        estado = EstadoEvento.FINALIZADO;
    }

    public void aplicarDisponibilidad(boolean agotado) {
        if (agotado && estado == EstadoEvento.PUBLICADO) estado = EstadoEvento.AGOTADO;
        else if (!agotado && estado == EstadoEvento.AGOTADO) estado = EstadoEvento.PUBLICADO;
        else if (estado == EstadoEvento.CANCELADO || estado == EstadoEvento.FINALIZADO) return;
        else if ((agotado && estado == EstadoEvento.AGOTADO) || (!agotado && estado == EstadoEvento.PUBLICADO)) return;
        else throw new IllegalStateException("El estado actual no admite el cambio de disponibilidad");
    }

    private void validarEstadoVentaEditable() {
        if (estado != EstadoEvento.PUBLICADO && estado != EstadoEvento.AGOTADO) {
            throw new IllegalStateException("Solo se puede modificar la venta de un evento publicado o agotado");
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrganizadorId() { return organizadorId; }
    public void setOrganizadorId(String organizadorId) { this.organizadorId = organizadorId; }
    public String getLugarId() { return lugarId; }
    public void setLugarId(String lugarId) { this.lugarId = lugarId; }
    public String getCiudad() { return ciudad; }
    public void setCiudad(String ciudad) { this.ciudad = ciudad; }
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
    public EstadoEvento getEstado() { return estado; }
    public void setEstado(EstadoEvento estado) { this.estado = estado; }
    public boolean isVentaSuspendida() { return ventaSuspendida; }
    public void setVentaSuspendida(boolean ventaSuspendida) { this.ventaSuspendida = ventaSuspendida; }
    public int getLimiteEntradasPorCliente() { return limiteEntradasPorCliente; }
    public void setLimiteEntradasPorCliente(int limiteEntradasPorCliente) { this.limiteEntradasPorCliente = limiteEntradasPorCliente; }
    public List<EventoSector> getSectores() { return sectores; }
    public void setSectores(List<EventoSector> sectores) { this.sectores = sectores; }
    public BigDecimal getPrecioMinimo() { return precioMinimo; }
    public void setPrecioMinimo(BigDecimal precioMinimo) { this.precioMinimo = precioMinimo; }
    public String getMotivoCancelacion() { return motivoCancelacion; }
    public void setMotivoCancelacion(String motivoCancelacion) { this.motivoCancelacion = motivoCancelacion; }
    public Instant getCanceladoEn() { return canceladoEn; }
    public void setCanceladoEn(Instant canceladoEn) { this.canceladoEn = canceladoEn; }
    public boolean isAvisoCancelacionPendiente() { return avisoCancelacionPendiente; }
    public void setAvisoCancelacionPendiente(boolean avisoCancelacionPendiente) { this.avisoCancelacionPendiente = avisoCancelacionPendiente; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
