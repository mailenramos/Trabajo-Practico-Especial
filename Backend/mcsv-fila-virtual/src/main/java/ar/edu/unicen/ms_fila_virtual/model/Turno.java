package ar.edu.unicen.ms_fila_virtual.model;

import java.time.LocalDateTime;

import ar.edu.unicen.ms_fila_virtual.model.enums.EstadoTurno;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "turnos", uniqueConstraints = {
        @UniqueConstraint(name = "uk_turno_usuario_solicitud", columnNames = {"usuario_id", "solicitud_id"})
})
public class Turno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fila_id", nullable = false)
    private FilaEvento fila;

    @Column(name = "usuario_id", nullable = false, length = 255)
    private String usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    private EstadoTurno estado = EstadoTurno.ESPERANDO;

    // DATETIME(6) stores UTC; the service converts using the same convention as MS2.
    @Column(name = "ingresado_en", nullable = false)
    private LocalDateTime ingresadoEn;

    @Column(name = "habilitado_en")
    private LocalDateTime habilitadoEn;

    @Column(name = "expira_en")
    private LocalDateTime expiraEn;

    @Column(name = "solicitud_id", length = 255)
    private String solicitudId;

    @Column(name = "huella_solicitud", length = 255)
    private String huellaSolicitud;

    public Turno() {
    }

    public Long getId() { return id; }

    public FilaEvento getFila() { return fila; }

    public void setFila(FilaEvento fila) { this.fila = fila; }

    public String getUsuarioId() { return usuarioId; }

    public void setUsuarioId(String usuarioId) { this.usuarioId = usuarioId; }

    public EstadoTurno getEstado() { return estado; }

    public void setEstado(EstadoTurno estado) { this.estado = estado; }

    public LocalDateTime getIngresadoEn() { return ingresadoEn; }

    public void setIngresadoEn(LocalDateTime ingresadoEn) { this.ingresadoEn = ingresadoEn; }

    public LocalDateTime getHabilitadoEn() { return habilitadoEn; }

    public void setHabilitadoEn(LocalDateTime habilitadoEn) { this.habilitadoEn = habilitadoEn; }

    public LocalDateTime getExpiraEn() { return expiraEn; }

    public void setExpiraEn(LocalDateTime expiraEn) { this.expiraEn = expiraEn; }

    public String getSolicitudId() { return solicitudId; }

    public void setSolicitudId(String solicitudId) { this.solicitudId = solicitudId; }

    public String getHuellaSolicitud() { return huellaSolicitud; }

    public void setHuellaSolicitud(String huellaSolicitud) { this.huellaSolicitud = huellaSolicitud; }
}
