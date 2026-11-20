package ticketya.dobles;

import ticketya.cliente.NotificacionesCliente;
import ticketya.cliente.ServicioExternoException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Doble de prueba de Notificaciones: guarda los avisos recibidos en memoria. Puede simular que no
 * responde (falla temporal) o que rechaza los avisos por inválidos (falla definitiva).
 */
public class NotificacionesFalso implements NotificacionesCliente {

    private final List<Aviso> recibidos = new CopyOnWriteArrayList<>();
    private volatile boolean caido;
    private volatile boolean rechazar;

    public void setCaido(boolean caido) { this.caido = caido; }
    public void setRechazar(boolean rechazar) { this.rechazar = rechazar; }
    public List<Aviso> recibidos() { return List.copyOf(recibidos); }

    public void reiniciar() {
        recibidos.clear();
        caido = false;
        rechazar = false;
    }

    @Override
    public void enviar(Aviso aviso) {
        if (caido) {
            throw new ServicioExternoException("Notificaciones", "Notificaciones no responde (doble)");
        }
        if (rechazar) {
            throw new AvisoRechazadoException("Notificaciones rechazó el aviso con 400 VALIDACION (doble)", null);
        }
        recibidos.add(aviso);
    }
}
