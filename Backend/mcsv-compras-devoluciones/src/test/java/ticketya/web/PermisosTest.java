package ticketya.web;

import org.junit.jupiter.api.Test;
import ticketya.error.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Permiso de administrador por pertenencia del rol configurado al conjunto de roles. */
class PermisosTest {

    private final Permisos permisos = new Permisos("ADMIN");

    private static UsuarioActual conRoles(String roles) {
        return UsuarioActual.conRolesSeparadosPorComa("u-1", roles);
    }

    @Test
    void elRolDeAdministradorSeComparaSinDistinguirMayusculas() {
        assertThat(permisos.esAdministrador(conRoles("ADMIN"))).isTrue();
        assertThat(permisos.esAdministrador(conRoles("admin"))).isTrue();
        assertThatCode(() -> permisos.exigirAdministrador(conRoles("Admin")))
                .doesNotThrowAnyException();
    }

    @Test
    void unAdministradorConOtrosRolesEsAdministrador() {
        assertThat(permisos.esAdministrador(conRoles("CLIENTE,ADMIN"))).isTrue();
        assertThat(permisos.esAdministrador(conRoles("ADMIN, PERSONAL_ACCESO, CLIENTE"))).isTrue();
        assertThatCode(() -> permisos.exigirAdministrador(conRoles("cliente, admin")))
                .doesNotThrowAnyException();
    }

    @Test
    void unUsuarioSinElRolRecibe403() {
        for (String roles : new String[]{"CLIENTE", null, "", "ADMINISTRADOR", "CLIENTE,PERSONAL_ACCESO"}) {
            assertThatThrownBy(() -> permisos.exigirAdministrador(conRoles(roles)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(403);
                        assertThat(e.getCodigo()).isEqualTo("SOLO_ADMINISTRADOR");
                    });
        }
    }

    @Test
    void elRolEsConfigurable() {
        Permisos otro = new Permisos("ORGANIZADOR");

        assertThat(otro.esAdministrador(conRoles("ORGANIZADOR"))).isTrue();
        assertThat(otro.esAdministrador(conRoles("cliente,organizador"))).isTrue();
        assertThat(otro.esAdministrador(conRoles("ADMIN"))).isFalse();
    }
}
