package ticketya.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import ticketya.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Identidad y roles desde los encabezados del API Gateway: los seis escenarios de la spec. */
class UsuarioActualResolverTest {

    private final UsuarioActualResolver resolver = new UsuarioActualResolver();

    private UsuarioActual resolver(String... encabezados) {
        MockHttpServletRequest pedido = new MockHttpServletRequest("GET", "/compras");
        for (int i = 0; i < encabezados.length; i += 2) {
            pedido.addHeader(encabezados[i], encabezados[i + 1]);
        }
        return resolver.resolveArgument(null, null, new ServletWebRequest(pedido), null);
    }

    @Test
    void unSoloRol() {
        UsuarioActual u = resolver("X-User-Id", "u1", "X-User-Roles", "CLIENTE");

        assertThat(u.id()).isEqualTo("u1");
        assertThat(u.roles()).containsExactly("CLIENTE");
    }

    @Test
    void variosRolesSinDistinguirMayusculasNiEspacios() {
        UsuarioActual u = resolver("X-User-Id", "u1", "X-User-Roles", "cliente, ADMIN");

        assertThat(u.roles()).containsExactlyInAnyOrder("CLIENTE", "ADMIN");
    }

    @Test
    void sinRolesElConjuntoEsVacio() {
        assertThat(resolver("X-User-Id", "u1").roles()).isEmpty();
        assertThat(resolver("X-User-Id", "u1", "X-User-Roles", "").roles()).isEmpty();
    }

    @Test
    void sinIdentificadorResponde401() {
        for (String[] encabezados : new String[][] {{}, {"X-User-Id", "  "}, {"X-User-Roles", "ADMIN"}}) {
            assertThatThrownBy(() -> resolver(encabezados))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(401);
                        assertThat(e.getCodigo()).isEqualTo("USUARIO_NO_IDENTIFICADO");
                    });
        }
    }

    @Test
    void elEncabezadoAnteriorDeUnSoloRolSeAceptaComoRespaldo() {
        UsuarioActual u = resolver("X-User-Id", "u1", "X-User-Role", "ADMIN");

        assertThat(u.roles()).containsExactly("ADMIN");
    }

    @Test
    void siLleganLosDosMandaElNuevo() {
        UsuarioActual u = resolver("X-User-Id", "u1", "X-User-Roles", "CLIENTE", "X-User-Role", "ADMIN");

        assertThat(u.roles()).containsExactly("CLIENTE");
    }

    @Test
    void elIdSeRecortaDeEspacios() {
        assertThat(resolver("X-User-Id", "  u1 ", "X-User-Roles", "CLIENTE").id()).isEqualTo("u1");
    }
}
