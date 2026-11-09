package ar.edu.unicen.ms_disponibilidad_reservas.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import ar.edu.unicen.ms_disponibilidad_reservas.model.enums.EstadoAsiento;

/**
 * Completa estados que pudieran haber quedado sin valor antes de que
 * AsientoDisponibilidad comenzara a persistir el enum.
 */
@Component
@DependsOn("entityManagerFactory")
public class InicializadorEstadoAsientos implements InitializingBean {

    private static final Set<String> ESTADOS_VALIDOS = Arrays.stream(EstadoAsiento.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final JdbcTemplate jdbcTemplate;

    public InicializadorEstadoAsientos(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.update(
                "UPDATE asientos_disponibilidad SET estado = 'LIBRE' "
                        + "WHERE estado IS NULL OR TRIM(estado) = ''");

        List<String> estadosPersistidos = jdbcTemplate.query(
                "SELECT DISTINCT estado FROM asientos_disponibilidad",
                (rs, rowNum) -> rs.getString("estado"));
        List<String> estadosInvalidos = estadosPersistidos.stream()
                .filter(estado -> !ESTADOS_VALIDOS.contains(estado))
                .map(estado -> estado == null ? "<NULL>" : "'" + estado + "'")
                .toList();
        if (!estadosInvalidos.isEmpty()) {
            throw new IllegalStateException(
                    "Hay estados no canónicos en asientos_disponibilidad: " + estadosInvalidos
                            + ". Los únicos valores válidos son " + ESTADOS_VALIDOS);
        }
    }
}
