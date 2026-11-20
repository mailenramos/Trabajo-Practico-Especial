package ticketya.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Notificación a enviar por correo. Se guarda solo el ID del usuario destinatario: el correo
 * se pide a Gestión de usuarios al enviar y no se persiste.
 */
@Entity
@Table(name = "notificacion",
        uniqueConstraints = @UniqueConstraint(name = "uk_notificacion_clave_deduplicacion",
                columnNames = "clave_deduplicacion"),
        indexes = @Index(name = "ix_notificacion_estado_proximo", columnList = "estado, proximo_intento"))
public class Notificacion {

    public static final int LARGO_MAXIMO_ERROR = 1000;

    @Id
    private UUID id;

    /** Acontecimiento + tipo + destinatario: dos avisos con la misma clave son el mismo aviso. */
    @Column(name = "clave_deduplicacion", nullable = false, length = 255)
    private String claveDeduplicacion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TipoNotificacion tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoNotificacion estado;

    @Column(name = "usuario_id", nullable = false, length = 100)
    private String usuarioId;

    @Column(name = "recurso_id", nullable = false, length = 100)
    private String recursoId;

    @Column(name = "correlacion_id", nullable = false, length = 100)
    private String correlacionId;

    @Column(name = "version_esquema", nullable = false)
    private int versionEsquema;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    @Convert(converter = DatosJsonConverter.class)
    @Column(name = "datos", nullable = false, columnDefinition = "TEXT")
    private Map<String, Object> datos = new LinkedHashMap<>();

    @Column(nullable = false)
    private int intentos;

    @Column(name = "proximo_intento", nullable = false)
    private Instant proximoIntento;

    @Column(name = "vence_procesamiento")
    private Instant venceProcesamiento;

    @Column(name = "ultimo_error", length = LARGO_MAXIMO_ERROR)
    private String ultimoError;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "enviada_en")
    private Instant enviadaEn;

    protected Notificacion() {
    }

    /** Notificación recién recibida: PENDIENTE y lista para su primer intento. */
    public static Notificacion nueva(String acontecimientoId, TipoNotificacion tipo, String usuarioId,
                                     String recursoId, String correlacionId, int versionEsquema,
                                     Instant ocurridoEn, Map<String, Object> datos, Instant ahora) {
        Notificacion n = new Notificacion();
        n.id = UUID.randomUUID();
        n.claveDeduplicacion = claveDe(acontecimientoId, tipo, usuarioId);
        n.tipo = tipo;
        n.estado = EstadoNotificacion.PENDIENTE;
        n.usuarioId = usuarioId;
        n.recursoId = recursoId;
        n.correlacionId = correlacionId;
        n.versionEsquema = versionEsquema;
        n.ocurridoEn = ocurridoEn;
        n.datos = datos == null ? new LinkedHashMap<>() : new LinkedHashMap<>(datos);
        n.intentos = 0;
        n.proximoIntento = ahora;
        n.creadaEn = ahora;
        return n;
    }

    /*
     * Los cambios de estado posteriores a la toma se hacen con actualizaciones condicionales en
     * NotificacionRepository (tomar, guardarEnviada, guardarReintento, guardarErrorFinal y la
     * recuperación), nunca guardando la entidad: así dos escrituras concurrentes no se pisan.
     */

    /** Recorta el error al largo de la columna. */
    public static String recortar(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= LARGO_MAXIMO_ERROR ? error : error.substring(0, LARGO_MAXIMO_ERROR);
    }

    public static String claveDe(String acontecimientoId, TipoNotificacion tipo, String usuarioId) {
        return acontecimientoId + "|" + tipo.name() + "|" + usuarioId;
    }

    public UUID getId() { return id; }
    public String getClaveDeduplicacion() { return claveDeduplicacion; }
    public TipoNotificacion getTipo() { return tipo; }
    public EstadoNotificacion getEstado() { return estado; }
    public String getUsuarioId() { return usuarioId; }
    public String getRecursoId() { return recursoId; }
    public String getCorrelacionId() { return correlacionId; }
    public int getVersionEsquema() { return versionEsquema; }
    public Instant getOcurridoEn() { return ocurridoEn; }
    public Map<String, Object> getDatos() { return datos; }
    public int getIntentos() { return intentos; }
    public Instant getProximoIntento() { return proximoIntento; }
    public Instant getVenceProcesamiento() { return venceProcesamiento; }
    public String getUltimoError() { return ultimoError; }
    public Instant getCreadaEn() { return creadaEn; }
    public Instant getEnviadaEn() { return enviadaEn; }
}
