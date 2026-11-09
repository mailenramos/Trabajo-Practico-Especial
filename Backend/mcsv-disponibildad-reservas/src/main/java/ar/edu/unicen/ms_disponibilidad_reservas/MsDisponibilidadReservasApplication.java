package ar.edu.unicen.ms_disponibilidad_reservas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MsDisponibilidadReservasApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsDisponibilidadReservasApplication.class, args);
	}

}
