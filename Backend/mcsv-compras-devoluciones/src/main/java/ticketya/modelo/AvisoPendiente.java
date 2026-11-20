package ticketya.modelo;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Aviso a Notificaciones guardado en la misma transacción que la operación que lo origina
 * (outbox). Una tarea programada lo envía después. La restricción única es la misma clave con la
 * que Notificaciones deduplica (acontecimiento, tipo y destinatario): los avisos de un evento a
 * cada comprador comparten acontecimientoId y conviven, y repetir el mismo no crea otro.
 * Las filas se insertan con {@code AvisoPendienteRepository#registrar}.
 */
@Entity
@Table(name = "avisos_pendientes",
        uniqueConstraints = @UniqueConstraint(name = "uk_aviso_acontecimiento_tipo_usuario",
                columnNames = {"acontecimiento_id", "tipo", "usuario_id"}),
        indexes = @Index(name = "ix_aviso_estado_proximo", columnList = "estado, proximo_intento"))
public class AvisoPendiente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "acontecimiento_id", nullable = false, length = 100)
    private String acontecimientoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 40)
    private TipoAviso tipo;

    @Column(name = "usuario_id", nullable = false, length = 64)
    private String usuarioId;

    @Column(name = "recurso_id", nullable = false, length = 100)
    private String recursoId;

    @Column(name = "correlacion_id", nullable = false, length = 36)
    private String correlacionId;

    // Datos para armar el correo, como objeto JSON (campos según el tipo)
    @Column(name = "datos", nullable = false, columnDefinition = "json")
    private String datos;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoAviso estado = EstadoAviso.PENDIENTE;

    @Column(name = "intentos", nullable = false)
    private int intentos;

    @Column(name = "proximo_intento", nullable = false)
    private Instant proximoIntento;

    @Column(name = "ultimo_error", length = 500)
    private String ultimoError;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    // Identifica la ejecución que lo tomó: el resultado del envío solo se guarda si sigue siendo suya
    @Column(name = "toma", length = 36)
    private String toma;

    // Mientras está PROCESANDO: pasado este momento se considera interrumpido y se recupera
    @Column(name = "vence_en")
    private Instant venceEn;

    @Column(name = "enviado_en")
    private Instant enviadoEn;

    protected AvisoPendiente() { }

    public Long getId() { return id; }
    public String getAcontecimientoId() { return acontecimientoId; }
    public TipoAviso getTipo() { return tipo; }
    public String getUsuarioId() { return usuarioId; }
    public String getRecursoId() { return recursoId; }
    public String getCorrelacionId() { return correlacionId; }
    public String getDatos() { return datos; }
    public EstadoAviso getEstado() { return estado; }
    public int getIntentos() { return intentos; }
    public Instant getProximoIntento() { return proximoIntento; }
    public String getUltimoError() { return ultimoError; }
    public Instant getCreadoEn() { return creadoEn; }
    public String getToma() { return toma; }
    public Instant getVenceEn() { return venceEn; }
    public Instant getEnviadoEn() { return enviadoEn; }

    /** Recorta un error al largo de la columna. */
    public static String recortar(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }
}
