package com.ticketya.msvc.entradas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class MsvcEntradasApplication {

    public static void main(String[] args) {
        // En Windows la JVM puede informar "America/Buenos_Aires" (nombre antiguo),
        // que PostgreSQL rechaza. Se fija UTC, que además es lo que usa el servicio.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MsvcEntradasApplication.class, args);
    }
}