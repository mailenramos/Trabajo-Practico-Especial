package com.ticketya.usuarios;

import com.ticketya.usuarios.config.AdminInicialProperties;
import com.ticketya.usuarios.config.JwtProperties;
import com.ticketya.usuarios.modelo.Rol;
import com.ticketya.usuarios.servicio.InicializacionUsuarios;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InicializacionTest extends PruebaApi {

    @Autowired
    private InicializacionUsuarios inicializacion;

    @Test
    void elAdminInicialSeCreaUnaSolaVezAunqueSeReinicie() {
        long antes = repositorio.count();
        inicializacion.run(new DefaultApplicationArguments());
        inicializacion.run(new DefaultApplicationArguments());

        assertThat(repositorio.count()).isEqualTo(antes);
        assertThat(repositorio.findByCorreo("admin.inicial@ticketya.test"))
                .hasValueSatisfying(admin -> assertThat(admin.getRoles()).containsExactly(Rol.ADMIN));
    }

    @Test
    void creaUnUsuarioDePruebaPorCadaRol() {
        for (Rol rol : Rol.values()) {
            assertThat(repositorio.findAll()).anyMatch(u -> u.getCorreo().endsWith("@ticketya.test") && u.tieneRol(rol));
        }
    }

    @Test
    void sinCredencialesDelAdminInicialElServicioNoArranca() {
        assertThatThrownBy(() -> new AdminInicialProperties("", "", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_EMAIL")
                .hasMessageContaining("ADMIN_PASSWORD");
        assertThatThrownBy(() -> new AdminInicialProperties("admin@x.test", null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lasPropiedadesNoMuestranCredenciales() {
        assertThat(new AdminInicialProperties("admin@x.test", "Clave12345", null, null).toString())
                .doesNotContain("admin@x.test").doesNotContain("Clave12345");
        assertThat(new JwtProperties("secreto-de-pruebas-con-al-menos-32-bytes", "iss", Duration.ofHours(1))
                .toString()).doesNotContain("secreto-de-pruebas");
    }

    @Test
    void elSecretoJwtDebeTenerAlMenos32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("corto", "iss", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
