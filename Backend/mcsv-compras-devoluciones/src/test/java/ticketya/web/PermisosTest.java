package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermisosTest {

    private final Permisos permisos = new Permisos("ADMIN");

    @Test
    void elRolDeAdministradorSeComparaSinDistinguirMayusculas() {
        assertThat(permisos.esAdministrador(new UsuarioActual("u-1", "ADMIN"))).isTrue();
        assertThat(permisos.esAdministrador(new UsuarioActual("u-1", "admin"))).isTrue();
        assertThatCode(() -> permisos.exigirAdministrador(new UsuarioActual("u-1", "Admin")))
                .doesNotThrowAnyException();
    }

    @Test
    void unUsuarioSinElRolRecibe403() {
        for (String rol : new String[]{"CLIENTE", null, "ADMINISTRADOR"}) {
            assertThatThrownBy(() -> permisos.exigirAdministrador(new UsuarioActual("u-1", rol)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(403);
                        assertThat(e.getCodigo()).isEqualTo("SOLO_ADMINISTRADOR");
                    });
        }
    }

    @Test
    void elRolEsConfigurable() {
        Permisos otro = new Permisos("ROLE_ADMIN");

        assertThat(otro.esAdministrador(new UsuarioActual("u-1", "role_admin"))).isTrue();
        assertThat(otro.esAdministrador(new UsuarioActual("u-1", "ADMIN"))).isFalse();
    }
}
