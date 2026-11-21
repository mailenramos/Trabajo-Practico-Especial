package ticketya;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Gestión de notificaciones: recibe avisos de otros servicios y envía correos de forma asíncrona. */
@SpringBootApplication
public class NotificacionesApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificacionesApplication.class, args);
	}
}
