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

    // ------------------------------------------------------------------ reportes

    private static UsuarioActual usuario(String id, String roles) {
        return UsuarioActual.conRolesSeparadosPorComa(id, roles);
    }

    private static void esperar403(Runnable accion, String codigo) {
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(403);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    @Test
    void elAdministradorVeLosReportesDeCualquierEvento() {
        assertThatCode(() -> permisos.exigirTitularidadDeEvento(usuario("admin-1", "ADMIN"), "organizador-9"))
                .doesNotThrowAnyException();
        assertThatCode(() -> permisos.exigirTitularidadDeEvento(usuario("admin-1", "ADMIN"), null))
                .doesNotThrowAnyException();
    }

    @Test
    void elOrganizadorTitularVeLosReportesDeSuEvento() {
        assertThatCode(() -> permisos.exigirTitularidadDeEvento(usuario("org-1", "organizador"), "org-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> permisos.exigirTitularidadDeEvento(usuario("org-1", "CLIENTE, ORGANIZADOR"), "org-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void unOrganizadorAjenoRecibeEventoAjeno() {
        esperar403(() -> permisos.exigirTitularidadDeEvento(usuario("org-2", "ORGANIZADOR"), "org-1"), "EVENTO_AJENO");
    }

    @Test
    void sinOrganizadorInformadoNingunOrganizadorEsTitular() {
        esperar403(() -> permisos.exigirTitularidadDeEvento(usuario("org-1", "ORGANIZADOR"), null), "EVENTO_AJENO");
    }

    @Test
    void sinRolDeOrganizadorNiAdministradorNoHayReportes() {
        for (String roles : new String[]{"CLIENTE", "", "PERSONAL_ACCESO", "ORGANIZADORES"}) {
            esperar403(() -> permisos.exigirRolDeReportes(usuario("org-1", roles)), "SIN_PERMISO_REPORTES");
            // Aunque el id coincida con el titular, sin el rol no alcanza
            esperar403(() -> permisos.exigirTitularidadDeEvento(usuario("org-1", roles), "org-1"),
                    "SIN_PERMISO_REPORTES");
        }
    }

    @Test
    void elRolOrganizadorEsConfigurable() {
        Permisos otro = new Permisos("ADMIN", "PRODUCTOR");

        assertThatCode(() -> otro.exigirTitularidadDeEvento(usuario("p-1", "PRODUCTOR"), "p-1"))
                .doesNotThrowAnyException();
        esperar403(() -> otro.exigirRolDeReportes(usuario("p-1", "ORGANIZADOR")), "SIN_PERMISO_REPORTES");
    }
}
