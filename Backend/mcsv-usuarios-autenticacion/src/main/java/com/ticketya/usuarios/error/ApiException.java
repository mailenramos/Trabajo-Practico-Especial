package com.ticketya.usuarios.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Error de negocio con estado HTTP y código del formato de error estándar. */
public class ApiException extends RuntimeException {

    private final HttpStatus estado;
    private final String codigo;
    private final Map<String, String> campos;

    public ApiException(HttpStatus estado, String codigo, String mensaje) {
        this(estado, codigo, mensaje, null);
    }

    public ApiException(HttpStatus estado, String codigo, String mensaje, Map<String, String> campos) {
        super(mensaje);
        this.estado = estado;
        this.codigo = codigo;
        this.campos = campos;
    }

    public static ApiException noEncontrado(String mensaje) {
        return new ApiException(HttpStatus.NOT_FOUND, "USUARIO_NO_ENCONTRADO", mensaje);
    }

    public static ApiException accesoDenegado(String mensaje) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", mensaje);
    }

    public static ApiException conflicto(String codigo, String mensaje) {
        return new ApiException(HttpStatus.CONFLICT, codigo, mensaje);
    }

    public static ApiException validacion(String mensaje, Map<String, String> campos) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDACION", mensaje, campos);
    }

    public static ApiException noAutenticado(String mensaje) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "FALTA_IDENTIFICACION", mensaje);
    }

    public HttpStatus getEstado() {
        return estado;
    }

    public String getCodigo() {
        return codigo;
    }

    public Map<String, String> getCampos() {
        return campos;
    }
}
