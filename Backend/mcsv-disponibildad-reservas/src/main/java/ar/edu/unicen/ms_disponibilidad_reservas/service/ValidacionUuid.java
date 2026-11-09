package ar.edu.unicen.ms_disponibilidad_reservas.service;

/** Checks syntax without trimming or normalization. */
public final class ValidacionUuid {
    private ValidacionUuid() { }
    public static void exigir(String valor, String parametro) {
        if (valor == null || !valor.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ParametroInvalidoException(parametro);
        }
    }
}
