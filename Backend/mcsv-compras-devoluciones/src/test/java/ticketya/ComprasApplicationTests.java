package ticketya;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"compras.cancelacion.reanudacion-activa=false",
        "compras.avisos.envio-activo=false"})
class ComprasApplicationTests {

	@Test
	void contextLoads() {
	}

}
