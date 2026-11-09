package ar.edu.unicen.ms_disponibilidad_reservas.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoReserva;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "reservas", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reserva_usuario_clave_idempotencia", columnNames = {
                "usuario_id", "clave_idempotencia"
        })
})
public class Reserva {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @NotNull
    @Column(name = "usuario_id", nullable = false)
    private String usuarioId;

    @NotNull
    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoReserva estado;

    @NotNull
    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @NotNull
    @Column(name = "clave_idempotencia", nullable = false)
    private String claveIdempotencia;

    @NotNull
    @Column(name = "huella_solicitud", nullable = false)
    private String huellaSolicitud;

    @OneToMany(mappedBy = "reserva", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetalleReserva> detalles = new ArrayList<>();

    public Reserva() {
    }

    @PrePersist
    private void asignarIdSiFalta() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(String usuarioId) {
        this.usuarioId = usuarioId;
    }

    public String getEventoId() {
        return eventoId;
    }

    public void setEventoId(String eventoId) {
        this.eventoId = eventoId;
    }

    public EstadoReserva getEstado() {
        return estado;
    }

    public void setEstado(EstadoReserva estado) {
        this.estado = estado;
    }

    public LocalDateTime getExpiraEn() {
        return expiraEn;
    }

    public void setExpiraEn(LocalDateTime expiraEn) {
        this.expiraEn = expiraEn;
    }

    public String getClaveIdempotencia() {
        return claveIdempotencia;
    }

    public void setClaveIdempotencia(String claveIdempotencia) {
        this.claveIdempotencia = claveIdempotencia;
    }

    public String getHuellaSolicitud() {
        return huellaSolicitud;
    }

    public void setHuellaSolicitud(String huellaSolicitud) {
        this.huellaSolicitud = huellaSolicitud;
    }

    public List<DetalleReserva> getDetalles() {
        return detalles;
    }

    public void agregarDetalle(DetalleReserva detalle) {
        detalles.add(detalle);
        detalle.setReserva(this);
    }

    public void quitarDetalle(DetalleReserva detalle) {
        detalles.remove(detalle);
        detalle.setReserva(null);
    }
}
