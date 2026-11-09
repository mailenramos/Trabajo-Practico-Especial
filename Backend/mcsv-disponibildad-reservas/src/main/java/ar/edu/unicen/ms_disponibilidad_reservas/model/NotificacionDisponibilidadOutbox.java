package ar.edu.unicen.ms_disponibilidad_reservas.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notificaciones_disponibilidad_outbox")
public class NotificacionDisponibilidadOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evento_id", nullable = false, length = 255)
    private String eventoId;

    @Column(nullable = false)
    private boolean agotado;

    @Column(name = "creada_en", nullable = false)
    private LocalDateTime creadaEn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EstadoNotificacionOutbox estado = EstadoNotificacionOutbox.PENDIENTE;

    @Column(nullable = false)
    private int intentos;

    @Column(name = "proximo_intento_en", nullable = false)
    private LocalDateTime proximoIntentoEn;

    @Column(name = "lease_hasta")
    private LocalDateTime leaseHasta;

    @Column(name = "token_lease", length = 36)
    private String tokenLease;

    @Column(name = "enviada_en")
    private LocalDateTime enviadaEn;

    @Column(name = "ultimo_error", length = 2000)
    private String ultimoError;

    protected NotificacionDisponibilidadOutbox() {
    }

    public NotificacionDisponibilidadOutbox(String eventoId, boolean agotado, LocalDateTime ahora) {
        this.eventoId = eventoId;
        this.agotado = agotado;
        this.creadaEn = ahora;
        this.proximoIntentoEn = ahora;
        this.estado = EstadoNotificacionOutbox.PENDIENTE;
        this.intentos = 0;
    }

    public Long getId() { return id; }
    public String getEventoId() { return eventoId; }
    public boolean isAgotado() { return agotado; }
    public LocalDateTime getCreadaEn() { return creadaEn; }
    public EstadoNotificacionOutbox getEstado() { return estado; }
    public int getIntentos() { return intentos; }
    public LocalDateTime getProximoIntentoEn() { return proximoIntentoEn; }
    public LocalDateTime getLeaseHasta() { return leaseHasta; }
    public String getTokenLease() { return tokenLease; }
    public LocalDateTime getEnviadaEn() { return enviadaEn; }
    public String getUltimoError() { return ultimoError; }

    public void reclamar(String token, LocalDateTime venceLease) {
        estado = EstadoNotificacionOutbox.PROCESANDO;
        tokenLease = token;
        leaseHasta = venceLease;
        intentos = Math.addExact(intentos, 1);
        enviadaEn = null;
    }

    public void marcarEnviada(LocalDateTime ahora) {
        estado = EstadoNotificacionOutbox.ENVIADA;
        enviadaEn = ahora;
        leaseHasta = null;
        tokenLease = null;
        ultimoError = null;
    }

    public void programarReintento(LocalDateTime siguienteIntento, String error) {
        estado = EstadoNotificacionOutbox.PENDIENTE;
        proximoIntentoEn = siguienteIntento;
        leaseHasta = null;
        tokenLease = null;
        enviadaEn = null;
        ultimoError = error;
    }
}
