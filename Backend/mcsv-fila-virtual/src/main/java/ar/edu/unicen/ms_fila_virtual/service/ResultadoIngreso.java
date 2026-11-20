package ar.edu.unicen.ms_fila_virtual.service;

import ar.edu.unicen.ms_fila_virtual.dto.TurnoResponse;

public record ResultadoIngreso(boolean creado, TurnoResponse turno) { }
