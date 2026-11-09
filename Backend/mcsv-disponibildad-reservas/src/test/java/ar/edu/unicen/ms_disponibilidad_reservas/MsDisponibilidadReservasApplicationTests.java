package ar.edu.unicen.ms_disponibilidad_reservas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:application-context;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false"
})
class MsDisponibilidadReservasApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void expirationSchedulerIsRegisteredInTheApplicationContext() {
		assertTrue(applicationContext.containsBean("reservaExpirationScheduler"));
	}

}
