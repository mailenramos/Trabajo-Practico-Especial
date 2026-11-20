package ticketya.web;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Los roles del usuario se normalizan: mayúsculas, sin espacios y sin vacíos. */
class UsuarioActualTest {

    @Test
    void losRolesQuedanEnMayusculasYSinEspacios() {
        UsuarioActual u = new UsuarioActual("u1", Set.of(" cliente ", "Admin", "personal acceso"));

        assertThat(u.roles()).containsExactlyInAnyOrder("CLIENTE", "ADMIN", "PERSONALACCESO");
    }

    @Test
    void losRolesVaciosONulosSeDescartan() {
        Set<String> conVacios = new HashSet<>(Arrays.asList("CLIENTE", "", "   ", null));

        assertThat(new UsuarioActual("u1", conVacios).roles()).containsExactly("CLIENTE");
    }

    @Test
    void sinRolesElConjuntoEsVacio() {
        assertThat(new UsuarioActual("u1", null).roles()).isEmpty();
        assertThat(new UsuarioActual("u1", Set.of()).roles()).isEmpty();
        assertThat(UsuarioActual.conRolesSeparadosPorComa("u1", null).roles()).isEmpty();
        assertThat(UsuarioActual.conRolesSeparadosPorComa("u1", " , ,").roles()).isEmpty();
    }

    @Test
    void unaListaSeparadaPorComaSeParteYSeNormaliza() {
        UsuarioActual u = UsuarioActual.conRolesSeparadosPorComa("u1", "cliente, ADMIN ,cliente");

        assertThat(u.roles()).containsExactlyInAnyOrder("CLIENTE", "ADMIN");
    }

    @Test
    void tieneRolNoDistingueMayusculasNiEspacios() {
        UsuarioActual u = UsuarioActual.conRolesSeparadosPorComa("u1", "CLIENTE,ADMIN");

        assertThat(u.tieneRol("admin")).isTrue();
        assertThat(u.tieneRol(" Admin ")).isTrue();
        assertThat(u.tieneRol("ORGANIZADOR")).isFalse();
        assertThat(u.tieneRol(null)).isFalse();
        assertThat(u.tieneRol("  ")).isFalse();
    }

    @Test
    void elConjuntoDeRolesNoSePuedeModificar() {
        UsuarioActual u = UsuarioActual.conRolesSeparadosPorComa("u1", "CLIENTE");

        assertThatThrownBy(() -> u.roles().add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
    }
}
